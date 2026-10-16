package app.hermesconduit.android.model

import app.hermesconduit.android.core.L10n
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import kotlin.math.log10

// region Text helpers (Swift CharacterSet semantics)

internal fun isSwiftWhitespace(cp: Int) = Character.isSpaceChar(cp) || cp in 0x09..0x0D || cp == 0x85

internal fun isSwiftPunctuation(cp: Int) = when (Character.getType(cp)) {
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt() -> true
    else -> false
}

internal fun String.trimCodePoints(strip: (Int) -> Boolean): String {
    val cps = codePointList()
    var start = 0
    var end = cps.size
    while (start < end && strip(cps[start])) start++
    while (end > start && strip(cps[end - 1])) end--
    return cps.subList(start, end).toCodePointString()
}

internal fun String.trimWhitespaceAndPunctuation() = trimCodePoints { isSwiftWhitespace(it) || isSwiftPunctuation(it) }

internal fun String.trimSwiftWhitespace() = trimCodePoints(::isSwiftWhitespace)

// endregion

// region Spoken commands

/**
 * Whole-utterance matching for the spoken Voice command lists (Stop, End
 * Conversation): a command fires only when the ENTIRE utterance equals an
 * ENTIRE configured phrase after normalization.
 */
object VoiceSpokenCommands {
    val legacyStopPhrases = listOf("stop", "stop talking", "be quiet", "停止", "别说了", "不要说了")
    val legacyEndConversationPhrases = listOf("goodbye", "bye", "end conversation", "that's all", "再见", "拜拜", "结束对话", "就这样吧")
    val previousDefaultStopPhrases = listOf("stop", "stop talking", "be quiet")
    val previousDefaultEndConversationPhrases = listOf("goodbye", "bye", "end conversation", "that's all")

    fun isUncustomized(stored: List<String>, legacy: List<List<String>>): Boolean {
        val canonicalStored = stored.map(::canonicalized).toSet()
        if (canonicalStored.isEmpty()) return false
        return legacy.any { list -> list.map(::canonicalized).toSet() == canonicalStored }
    }

    /** The list to persist after an edit: null (follow the app language) when it leaves exactly the built-ins. */
    fun storedPhrases(phrases: List<String>, builtIns: List<String>): List<String>? {
        val canonical = canonicalizedPhraseList(phrases)
        return if (isUncustomized(canonical, listOf(builtIns))) null else canonical
    }

    fun canonicalized(text: String): String = text.lowercase().replace('\u2019', '\'').trimWhitespaceAndPunctuation()

    fun matches(utterance: String, phrases: List<String>): Boolean {
        val normalized = canonicalized(utterance)
        if (normalized.isEmpty()) return false
        return phrases.any { canonicalized(it) == normalized }
    }

    private val leadingCourtesies = listOf(
        listOf("all", "right"), listOf("thank", "you"),
        listOf("ok"), listOf("okay"), listOf("alright"), listOf("thanks"), listOf("cool"), listOf("great"), listOf("well"),
        listOf("so"), listOf("please"), listOf("好的"), listOf("好"), listOf("谢谢"),
    )
    private val trailingCourtesies = listOf(
        listOf("for", "now"), listOf("thank", "you"),
        listOf("thanks"), listOf("now"), listOf("then"), listOf("please"), listOf("谢谢"),
    )

    /** Like [matches], but tolerant of how speech is transcribed: inner punctuation, up to three phrases, courtesies. */
    fun matchesSpokenCommand(utterance: String, phrases: List<String>): Boolean {
        if (matches(utterance, phrases)) return true
        val words = commandWords(utterance).toMutableList()
        stripCourtesies(words)
        val spoken = words.joinToString("")
        if (spoken.isEmpty()) return false
        val keys = phrases.map { phrase ->
            val phraseWords = commandWords(phrase).toMutableList()
            stripCourtesies(phraseWords)
            phraseWords.joinToString("")
        }.filter { it.isNotEmpty() }.toSet()
        return isSequence(spoken, keys, 3)
    }

    private fun isSequence(text: String, keys: Set<String>, limit: Int): Boolean {
        if (limit <= 0) return false
        return keys.any { key ->
            if (!text.startsWith(key)) return@any false
            val rest = text.substring(key.length)
            rest.isEmpty() || isSequence(rest, keys, limit - 1)
        }
    }

    val hangUpPhrases = listOf(
        "hang up", "end the call", "end this call", "end call",
        "end the conversation", "end this conversation", "close the call",
        "you can hang up", "go ahead and hang up",
    )

    fun asksToEndCall(utterance: String, endPhrases: List<String>): Boolean {
        if (endPhrases.isEmpty()) return false
        return matchesSpokenCommand(utterance, endPhrases + hangUpPhrases)
    }

    fun isEndCallMarker(request: String): Boolean =
        Regex("^\\s*end[ -]?(?:(?:the|this)\\s+)?call\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(request)

    private fun commandWords(text: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        canonicalized(text).codePoints().forEach { cp ->
            val separator = (isSwiftWhitespace(cp) || isSwiftPunctuation(cp)) && cp != '\''.code
            if (separator) {
                if (current.isNotEmpty()) words += current.toString()
                current.setLength(0)
            } else {
                current.appendCodePoint(cp)
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        return words.map { it.trim('\'') }.filter { it.isNotEmpty() }
    }

    private fun stripCourtesies(words: MutableList<String>) {
        var changed = true
        while (changed) {
            changed = false
            for (courtesy in leadingCourtesies) {
                if (words.size > courtesy.size && words.take(courtesy.size) == courtesy) {
                    repeat(courtesy.size) { words.removeAt(0) }
                    changed = true
                }
            }
            for (courtesy in trailingCourtesies) {
                if (words.size > courtesy.size && words.takeLast(courtesy.size) == courtesy) {
                    repeat(courtesy.size) { words.removeAt(words.size - 1) }
                    changed = true
                }
            }
        }
    }

    /** Trim each entry, drop blanks, de-duplicate by canonical form keeping the first display form. */
    fun canonicalizedPhraseList(phrases: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (phrase in phrases) {
            val trimmed = phrase.trimSwiftWhitespace()
            val canonical = canonicalized(trimmed)
            if (canonical.isEmpty() || !seen.add(canonical)) continue
            result += trimmed
        }
        return result
    }
}

/** The built-in spoken Stop and End Conversation phrases, in the app language ("stop|stop talking|be quiet"). */
object VoiceSpokenCommandDefaults {
    val englishStopPhrases get() = VoiceSpokenCommands.previousDefaultStopPhrases
    val englishEndConversationPhrases get() = VoiceSpokenCommands.previousDefaultEndConversationPhrases

    val stopPhrases: List<String>
        get() = phrases(L10n.tr("Built-in stop phrases"), "Built-in stop phrases", englishStopPhrases)

    val endConversationPhrases: List<String>
        get() = phrases(L10n.tr("Built-in end conversation phrases"), "Built-in end conversation phrases", englishEndConversationPhrases)

    fun phrases(catalogValue: String, key: String, fallback: List<String>): List<String> {
        if (catalogValue == key) return fallback
        val phrases = VoiceSpokenCommands.canonicalizedPhraseList(catalogValue.split("|"))
        return phrases.ifEmpty { fallback }
    }
}

// endregion

// region Preferences

enum class VoiceTranscriptionMode(val raw: String) {
    HERMES("hermes"),
    ON_DEVICE("appleOnDevice"),
    SPEECH_SERVER("speechServer");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Per-profile Voice preferences, stored as the iOS `VoiceProfilePreferences`
 * JSON blob (same key names). Unknown keys from a newer build are kept.
 */
data class VoiceProfilePreferences(
    val outputMuted: Boolean = false,
    val continuousConversation: Boolean = true,
    val keepListeningWhenLocked: Boolean? = null,
    val spokenStopPhrases: List<String>? = null,
    val spokenEndConversationPhrases: List<String>? = null,
    val transcriptionMode: VoiceTranscriptionMode? = null,
    val geminiLiveEnabled: Boolean = false,
    val gptLiveEnabled: Boolean = false,
    val grokLiveEnabled: Boolean = false,
    val voiceJobModel: String? = null,
    val voiceJobProvider: String? = null,
    val voiceJobReasoningEffort: String? = null,
    val saveVoiceCalls: Boolean? = null,
    val liveVoiceSpeakerBargeIn: Boolean? = null,
    /** Everything else in the blob, written back untouched. */
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    val resolvedSpokenStopPhrases: List<String> get() = spokenStopPhrases ?: VoiceSpokenCommandDefaults.stopPhrases
    val resolvedSpokenEndConversationPhrases: List<String> get() = spokenEndConversationPhrases ?: VoiceSpokenCommandDefaults.endConversationPhrases
    val resolvedTranscriptionMode: VoiceTranscriptionMode get() = transcriptionMode ?: VoiceTranscriptionMode.HERMES

    /** What a voice job's `session.create` asks for. */
    fun voiceJobSessionOptions(runtimeModel: String, runtimeProvider: String): Triple<String?, String?, String?> {
        val reasoning = voiceJobReasoningEffort?.takeIf { it.isNotEmpty() }
        voiceJobModel?.takeIf { it.isNotEmpty() }?.let { return Triple(it, voiceJobProvider?.takeIf(String::isNotEmpty), reasoning) }
        return Triple(runtimeModel.ifEmpty { null }, runtimeProvider.ifEmpty { null }, reasoning)
    }

    fun toJson(): JsonObject {
        val map = extra.toMutableMap()
        fun put(key: String, value: JsonElement?) { if (value == null) map.remove(key) else map[key] = value }
        put("outputMuted", JsonPrimitive(outputMuted))
        put("continuousConversation", JsonPrimitive(continuousConversation))
        put("continueWakeConversation", extra["continueWakeConversation"] ?: JsonPrimitive(false))
        put("keepListeningWhenLocked", keepListeningWhenLocked?.let(::JsonPrimitive))
        put("spokenStopPhrases", spokenStopPhrases?.let { list -> JsonArray(list.map(::JsonPrimitive)) })
        put("spokenEndConversationPhrases", spokenEndConversationPhrases?.let { list -> JsonArray(list.map(::JsonPrimitive)) })
        put("transcriptionMode", transcriptionMode?.raw?.let(::JsonPrimitive))
        put("geminiLiveEnabled", JsonPrimitive(geminiLiveEnabled))
        put("gptLiveEnabled", JsonPrimitive(gptLiveEnabled))
        put("grokLiveEnabled", JsonPrimitive(grokLiveEnabled))
        put("voiceJobModel", voiceJobModel?.let(::JsonPrimitive))
        put("voiceJobProvider", voiceJobProvider?.let(::JsonPrimitive))
        put("voiceJobReasoningEffort", voiceJobReasoningEffort?.let(::JsonPrimitive))
        put("saveVoiceCalls", saveVoiceCalls?.let(::JsonPrimitive))
        put("liveVoiceSpeakerBargeIn", liveVoiceSpeakerBargeIn?.let(::JsonPrimitive))
        return JsonObject(map)
    }

    companion object {
        private val known = setOf(
            "outputMuted", "continuousConversation", "keepListeningWhenLocked", "spokenStopPhrases",
            "spokenEndConversationPhrases", "transcriptionMode", "geminiLiveEnabled", "gptLiveEnabled", "grokLiveEnabled",
            "voiceJobModel", "voiceJobProvider", "voiceJobReasoningEffort", "saveVoiceCalls", "liveVoiceSpeakerBargeIn",
        )

        /** Missing keys decode to defaults; uncustomized built-in lists go back to following the app language. */
        fun parse(json: String?): VoiceProfilePreferences {
            val obj = json?.let { runCatching { ConduitJson.parseToJsonElement(it).obj }.getOrNull() } ?: return VoiceProfilePreferences()
            fun strings(key: String): List<String>? = (obj[key] as? JsonArray)?.mapNotNull { it.str }
            val stop = strings("spokenStopPhrases")?.takeUnless {
                VoiceSpokenCommands.isUncustomized(it, listOf(VoiceSpokenCommands.previousDefaultStopPhrases, VoiceSpokenCommands.legacyStopPhrases))
            }
            val end = strings("spokenEndConversationPhrases")?.takeUnless {
                VoiceSpokenCommands.isUncustomized(it, listOf(VoiceSpokenCommands.previousDefaultEndConversationPhrases, VoiceSpokenCommands.legacyEndConversationPhrases))
            }
            return VoiceProfilePreferences(
                outputMuted = obj["outputMuted"].bool ?: false,
                continuousConversation = obj["continuousConversation"].bool ?: true,
                keepListeningWhenLocked = obj["keepListeningWhenLocked"].bool,
                spokenStopPhrases = stop,
                spokenEndConversationPhrases = end,
                transcriptionMode = VoiceTranscriptionMode.of(obj["transcriptionMode"].str),
                geminiLiveEnabled = obj["geminiLiveEnabled"].bool ?: false,
                gptLiveEnabled = obj["gptLiveEnabled"].bool ?: false,
                grokLiveEnabled = obj["grokLiveEnabled"].bool ?: false,
                voiceJobModel = obj["voiceJobModel"].str,
                voiceJobProvider = obj["voiceJobProvider"].str,
                voiceJobReasoningEffort = obj["voiceJobReasoningEffort"].str,
                saveVoiceCalls = obj["saveVoiceCalls"].bool,
                liveVoiceSpeakerBargeIn = obj["liveVoiceSpeakerBargeIn"].bool,
                extra = JsonObject(obj.filterKeys { it !in known && obj[it] !is JsonNull }),
            )
        }
    }
}

/** Read Aloud playback speed: a device-wide choice. Voice conversations always play at 1x. */
enum class ReadAloudSpeed(val rate: Float) {
    NORMAL(1.0f), FAST(1.25f), FASTER(1.5f), FASTEST(1.75f), DOUBLE(2.0f);

    /** "1x", "1.25x", … the same in every locale. */
    val label: String get() = BigDecimal(rate.toDouble()).stripTrailingZeros().toPlainString() + "x"

    companion object {
        fun of(stored: Float): ReadAloudSpeed = entries.firstOrNull { it.rate == stored } ?: NORMAL
    }
}

// endregion

// region Background job commands and profiles

sealed interface VoiceBackgroundJobCommand {
    data class Start(val instructions: String) : VoiceBackgroundJobCommand
    data object Status : VoiceBackgroundJobCommand
    data object CancelAll : VoiceBackgroundJobCommand
}

object VoiceBackgroundJobCommands {
    val startPrefixes = listOf("start a background job", "start background job", "run in the background", "background job", "在后台运行", "后台任务")
    val statusPhrases = listOf("job status", "background jobs", "background job status", "check background jobs", "后台任务状态")
    val cancelPhrases = listOf("cancel background jobs", "cancel all background jobs", "stop background jobs", "取消后台任务")

    fun parse(utterance: String): VoiceBackgroundJobCommand? {
        if (VoiceSpokenCommands.matches(utterance, cancelPhrases)) return VoiceBackgroundJobCommand.CancelAll
        if (VoiceSpokenCommands.matches(utterance, statusPhrases)) return VoiceBackgroundJobCommand.Status
        val cps = utterance.codePointList()
        val first = cps.indexOfFirst { !(isSwiftWhitespace(it) || isSwiftPunctuation(it)) }
        if (first < 0) return null
        val trimmed = cps.subList(first, cps.size).toCodePointString()
        val folded = trimmed.lowercase().replace('\u2019', '\'')
        for (prefix in startPrefixes) {
            if (!folded.startsWith(prefix)) continue
            val afterPrefix = trimmed.substring(minOf(prefix.length, trimmed.length))
            val next = afterPrefix.codePointList().firstOrNull()
            if (next != null && (Character.isLetterOrDigit(next)) && prefix.all { it.code < 128 }) continue
            val task = afterPrefix.trimWhitespaceAndPunctuation()
            if (task.isEmpty()) return null
            return VoiceBackgroundJobCommand.Start(task)
        }
        return null
    }
}

sealed interface VoiceJobProfileTarget {
    data object Active : VoiceJobProfileTarget
    data class Other(val profile: String) : VoiceJobProfileTarget
    data object Unknown : VoiceJobProfileTarget
}

object VoiceJobProfiles {
    sealed interface Route {
        data class Run(val instructions: String, val profile: String?, val label: String?) : Route
        data class Unknown(val name: String) : Route
    }

    data class LeadingTarget(val target: VoiceJobProfileTarget, val name: String, val remainder: String)

    fun route(instructions: String, spokenProfile: String?, resolve: (String) -> VoiceJobProfileTarget): Route {
        var task = instructions
        var profile: String? = null
        var label: String? = null
        val spoken = spokenProfile?.trim()
        if (!spoken.isNullOrEmpty()) {
            when (val target = resolve(spoken)) {
                VoiceJobProfileTarget.Active -> Unit
                is VoiceJobProfileTarget.Other -> {
                    profile = target.profile
                    label = spoken
                }
                VoiceJobProfileTarget.Unknown -> return Route.Unknown(spoken)
            }
            val lead = leadingTarget(task, resolve)
            val expected: VoiceJobProfileTarget = profile?.let { VoiceJobProfileTarget.Other(it) } ?: VoiceJobProfileTarget.Active
            if (lead != null && lead.target == expected) task = lead.remainder
        } else {
            leadingTarget(task, resolve)?.let { lead ->
                if (lead.target is VoiceJobProfileTarget.Other) {
                    profile = lead.target.profile
                    label = lead.name
                }
                task = lead.remainder
            }
        }
        return Route.Run(task, profile, label)
    }

    /** "for Fam, check the router" → (Fam, "check the router"). Only for/on/with plus a known name (up to three words). */
    fun leadingTarget(instructions: String, resolve: (String) -> VoiceJobProfileTarget): LeadingTarget? {
        val words = instructions.split(' ', '\n').filter { it.isNotEmpty() }
        if (words.size < 3 || words[0].lowercase() !in setOf("for", "on", "with")) return null
        for (length in minOf(3, words.size - 2) downTo 1) {
            val name = words.subList(1, length + 1).joinToString(" ").trimWhitespaceAndPunctuation()
            if (name.isEmpty()) continue
            val target = resolve(name)
            if (target == VoiceJobProfileTarget.Unknown) continue
            val remainder = words.subList(length + 1, words.size).joinToString(" ").trimWhitespaceAndPunctuation()
            if (remainder.isEmpty()) return null
            return LeadingTarget(target, name, remainder)
        }
        return null
    }
}

// endregion

// region Dictation

enum class DictationEngine(val raw: String) {
    /** Android's speech recognizer, on the device when the language allows. */
    DEVICE("device"),
    /** The speech-to-text the connected Hermes uses. */
    HERMES("hermes"),
    /** The user's own speech server, through the notifier's relay. */
    SPEECH_SERVER("speechServer");

    companion object {
        fun resolved(raw: String?, isSupported: (DictationEngine) -> Boolean = { true }): DictationEngine {
            val engine = entries.firstOrNull { it.raw == raw } ?: return DEVICE
            return if (isSupported(engine)) engine else DEVICE
        }
    }
}

/** Words dictation should expect: names, companies, product names. */
object DictationCustomWords {
    const val MAXIMUM_COUNT = 100
    const val MAXIMUM_LENGTH = 60

    fun canonicalized(word: String): String = word.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(MAXIMUM_LENGTH)

    fun canonicalized(words: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (raw in words) {
            val word = canonicalized(raw)
            if (word.isEmpty() || !seen.add(word.lowercase())) continue
            result += word
            if (result.size == MAXIMUM_COUNT) break
        }
        return result
    }

    fun decode(stored: String): List<String> = canonicalized(stored.split('\n'))

    fun encode(words: List<String>): String = canonicalized(words).joinToString("\n")
}

/** Where dictated text goes in the draft: after what was there when dictation began. */
object ComposerDictation {
    enum class Tap { START, STOP, CANCEL_START, NOTHING }

    fun tap(isCapturing: Boolean, isStarting: Boolean, canDictate: Boolean, isTranscribing: Boolean = false): Tap = when {
        isCapturing -> Tap.STOP
        isStarting -> Tap.CANCEL_START
        isTranscribing -> Tap.NOTHING
        canDictate -> Tap.START
        else -> Tap.NOTHING
    }

    fun draftIsAsDictationLeftIt(text: String, prefix: String, lastWrite: String?): Boolean = text == (lastWrite ?: prefix)

    fun draft(prefix: String, dictated: String): String {
        val words = dictated.trim()
        if (words.isEmpty()) return prefix
        val last = prefix.lastOrNull() ?: return words
        return if (last.isWhitespace()) prefix + words else "$prefix $words"
    }
}

/** The whole of one dictation: finished utterances are kept when the recognizer starts the next from empty. */
class DictationTranscript {
    var committed = ""
        private set
    var current = ""
        private set
    private var atBoundary = false

    val text: String get() = join(committed, current)

    fun receive(result: String, endsUtterance: Boolean) {
        try {
            val words = words(result)
            if (committed.isNotEmpty() && !words.startsWith(words(current)) && words.startsWith(words(committed))) {
                committed = ""
                current = result
                return
            }
            if (startsOver(current, result, atBoundary)) committed = join(committed, current)
            current = result
        } finally {
            atBoundary = endsUtterance
        }
    }

    companion object {
        fun startsOver(previous: String, next: String, afterBoundary: Boolean): Boolean {
            val before = words(previous)
            if (before.isEmpty()) return false
            val after = words(next)
            val shared = before.zip(after).takeWhile { it.first == it.second }.size
            if (shared == before.size) return false
            if (afterBoundary) return after.size < before.size || shared * 2 <= before.size
            if (before.size < 3 || after.size * 2 > before.size) return false
            return shared == 0
        }

        private fun words(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

        private fun List<String>.startsWith(prefix: List<String>) = size >= prefix.size && subList(0, prefix.size) == prefix

        fun join(first: String, second: String): String {
            val a = first.trim()
            val b = second.trim()
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            return "$a $b"
        }
    }
}

/**
 * The language speech recognition should listen for: the one the person
 * speaks, not the app's UI language (#463). Tags are BCP-47 ("ru-RU").
 */
object SpeechRecognitionLocale {
    data class Tag(val language: String, val script: String?, val region: String?) {
        val identifier: String get() = listOfNotNull(language, script, region).joinToString("-")

        companion object {
            fun parse(raw: String): Tag? {
                val parts = raw.replace('_', '-').split('-').filter { it.isNotEmpty() }
                val language = parts.firstOrNull()?.lowercase()?.takeIf { it.length in 2..3 && it.all(Char::isLetter) } ?: return null
                var script: String? = null
                var region: String? = null
                for (part in parts.drop(1)) {
                    when {
                        part.length == 4 && part.all(Char::isLetter) && script == null && region == null ->
                            script = part.lowercase().replaceFirstChar { it.uppercase() }
                        (part.length == 2 && part.all(Char::isLetter)) || (part.length == 3 && part.all(Char::isDigit)) ->
                            if (region == null) region = part.uppercase()
                    }
                }
                return Tag(language, script, region)
            }
        }
    }

    private val homeRegions = mapOf(
        "en" to "US", "ru" to "RU", "fr" to "FR", "de" to "DE", "es" to "ES", "it" to "IT", "ja" to "JP", "ko" to "KR",
        "pt" to "BR", "tr" to "TR", "pl" to "PL", "id" to "ID", "nl" to "NL", "sv" to "SE", "da" to "DK", "nb" to "NO",
        "fi" to "FI", "uk" to "UA", "cs" to "CZ", "el" to "GR", "he" to "IL", "ar" to "SA", "hi" to "IN", "th" to "TH",
        "vi" to "VN", "ms" to "MY", "ro" to "RO", "hu" to "HU", "ca" to "ES", "hr" to "HR", "sk" to "SK",
        "zh-Hans" to "CN", "zh-Hant" to "TW", "yue-Hant" to "HK", "yue-Hans" to "CN",
    )

    /** The likely script for a language and region (zh-TW → Hant, ru → Cyrl…); null when Latin or unknown. */
    fun likelyScript(tag: Tag): String? {
        tag.script?.let { return it }
        return when (tag.language) {
            "zh" -> if (tag.region in setOf("TW", "HK", "MO")) "Hant" else "Hans"
            "yue" -> if (tag.region == "CN") "Hans" else "Hant"
            "ru", "uk", "be", "bg", "sr", "mk", "kk" -> "Cyrl"
            "ja" -> "Jpan"
            "ko" -> "Kore"
            "ar", "fa", "ur" -> "Arab"
            "he" -> "Hebr"
            "el" -> "Grek"
            "hi", "mr", "ne" -> "Deva"
            "th" -> "Thai"
            else -> "Latn"
        }
    }

    private fun homeRegion(language: String, script: String?): String? =
        homeRegions["$language-$script"] ?: homeRegions[language]

    /**
     * The first preferred language the recognizer supports (and [accepts]),
     * then [current] itself, falling back to [current] when neither is.
     */
    fun preferred(
        preferredLanguages: List<String>,
        current: String,
        supported: Collection<String>,
        accepts: (String) -> Boolean = { true },
    ): String {
        val currentTag = Tag.parse(current)
        val candidates = supported.mapNotNull(Tag::parse)
        for (wanted in preferredLanguages.mapNotNull(Tag::parse) + listOfNotNull(currentTag)) {
            bestMatch(wanted, currentTag, candidates, accepts)?.let { return it.identifier }
        }
        return currentTag?.identifier ?: current
    }

    private fun bestMatch(wanted: Tag, current: Tag?, supported: List<Tag>, accepts: (String) -> Boolean): Tag? {
        val script = likelyScript(wanted)
        val candidates = supported
            .filter { it.language == wanted.language && (likelyScript(it) == script) }
            .filter { accepts(it.identifier) }
            .sortedBy { it.identifier }
        if (candidates.isEmpty()) return null
        val regions = listOfNotNull(wanted.region, current?.region, homeRegion(wanted.language, script))
        for (region in regions) candidates.firstOrNull { it.region == region }?.let { return it }
        return candidates.first()
    }
}

/** Linear PCM amplitude → 0…1 display fraction on a dBFS scale (−60 dBFS empty, 0 dBFS full). */
object VoiceLevelMeterMath {
    fun displayFraction(level: Float): Double {
        if (!(level > 0)) return 0.0
        if (!level.isFinite()) return 1.0
        val decibels = 20.0 * log10(level.toDouble())
        return ((decibels + 60) / 60).coerceIn(0.0, 1.0)
    }
}

// endregion

/**
 * Hermes passes its speech provider's error through as is (for Edge TTS that
 * includes a Microsoft URL with its client token). People see what went
 * wrong; links and tokens never reach the screen.
 */
object HermesSpeechFailure {
    fun message(raw: String): String {
        val lowered = raw.lowercase()
        val isEdge = lowered.contains("(edge)") || lowered.contains("speech.platform.bing.com")
        val refusal = Regex("\\b40[13],|\\b(status|code|http)\\W{0,3}40[13]\\b|\\b40[13] (forbidden|unauthorized)")
        if (isEdge && refusal.containsMatchIn(lowered)) {
            return L10n.tr("Microsoft turned away Edge TTS because the edge-tts package on your Hermes host is out of date. Update it there (pip install -U edge-tts) and restart Hermes, or choose another speech provider in Settings > Voice.")
        }
        return cleaned(raw)
    }

    fun cleaned(raw: String): String {
        var text = raw
        for (pattern in listOf(",?\\s*(?<![\\w-])url=(['\"]).*?\\1", "\\b(?:wss?|https?)://\\S+")) {
            text = Regex(pattern).replace(text, "")
        }
        text = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (text.length <= 240) return text.ifEmpty { L10n.tr("Hermes speech stream failed.") }
        return text.take(240) + "…"
    }
}
