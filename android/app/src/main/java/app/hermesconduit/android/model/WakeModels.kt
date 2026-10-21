package app.hermesconduit.android.model

import app.hermesconduit.android.core.L10n
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer

/*
 * Foreground wake phrase (#174), ports of Conduit/Voice/Wake: the model
 * descriptor, the phrase compiler and matcher, the device-local
 * configuration and the lifecycle snapshot. Pure, so the rules are
 * unit-testable without audio.
 */

// region Model descriptor

data class WakeModelAssetDescriptor(
    val relativePath: String,
    val purpose: String,
    /** Null means Conduit has not yet accepted a checksum for redistribution. */
    val sha256: String?,
    val checksumStatus: ChecksumStatus,
) {
    enum class ChecksumStatus(val raw: String) { NOT_RECORDED("notRecorded"), VERIFIED("verified") }
    val id: String get() = relativePath
}

data class WakeModelDescriptor(
    val id: String,
    val displayName: String,
    val sherpaONNXVersion: String,
    val modelRelease: String,
    val sourceURL: String,
    val documentationURL: String,
    /** Published by GitHub for the complete upstream archive; a download is checked against it. */
    val archiveSHA256: String,
    val packagingStatus: PackagingStatus,
    val licenseReviewNote: String,
    val assets: List<WakeModelAssetDescriptor>,
) {
    enum class PackagingStatus(val raw: String) { BLOCKED_PENDING_LICENSE_REVIEW("blockedPendingLicenseReview"), READY_FOR_BUNDLING("readyForBundling") }

    /** The folder the archive unpacks into. */
    val archiveFolder: String get() = sourceURL.substringAfterLast('/').removeSuffix(".tar.bz2")

    val encoder: String get() = assets[0].relativePath
    val decoder: String get() = assets[1].relativePath
    val joiner: String get() = assets[2].relativePath
    val tokens: String get() = assets[3].relativePath
    val lexicon: String get() = assets[4].relativePath

    companion object {
        /**
         * The only v1 pack, as on iOS. Android never bundles it: it is
         * downloaded on the user's request and checked against [archiveSHA256].
         */
        val bundledBilingualPack: WakeModelDescriptor get() = WakeModelDescriptor(
            id = "sherpa-onnx-kws-zipformer-zh-en-3m-chunk8",
            displayName = L10n.tr("English + Chinese wake phrases"),
            sherpaONNXVersion = "1.13.2",
            modelRelease = "2025-12-20",
            sourceURL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2",
            documentationURL = "https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html",
            archiveSHA256 = "68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6",
            packagingStatus = PackagingStatus.BLOCKED_PENDING_LICENSE_REVIEW,
            licenseReviewNote = "Model redistribution terms and upstream SHA-256 values must be verified before assets are downloaded or committed.",
            assets = listOf(
                WakeModelAssetDescriptor("encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx", L10n.tr("quantized encoder"), null, WakeModelAssetDescriptor.ChecksumStatus.NOT_RECORDED),
                WakeModelAssetDescriptor("decoder-epoch-13-avg-2-chunk-8-left-64.onnx", L10n.tr("decoder"), null, WakeModelAssetDescriptor.ChecksumStatus.NOT_RECORDED),
                WakeModelAssetDescriptor("joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx", L10n.tr("quantized joiner"), null, WakeModelAssetDescriptor.ChecksumStatus.NOT_RECORDED),
                WakeModelAssetDescriptor("tokens.txt", L10n.tr("keyword tokens"), null, WakeModelAssetDescriptor.ChecksumStatus.NOT_RECORDED),
                WakeModelAssetDescriptor("en.phone", L10n.tr("English pronunciation lexicon"), null, WakeModelAssetDescriptor.ChecksumStatus.NOT_RECORDED),
            ),
        )
    }
}

// endregion

// region Bindings and compiler

data class WakeProfileKey(val gatewayID: String, val profileID: String) {
    companion object {
        fun of(gatewayID: String, profileID: String) = WakeProfileKey(gatewayID.trim(), profileID.trim())
    }
}

data class WakePhraseBinding(val key: WakeProfileKey, val phrase: String, val startsFreshConversation: Boolean) {
    val normalizedPhrase: String get() = WakePhraseCompiler.normalize(phrase)
    val id: String get() = "${key.gatewayID}|${key.profileID}|$normalizedPhrase"
}

data class CompiledWakePhrase(
    val phrase: String,
    val tokens: List<String>,
    val alias: String,
    val profileKey: WakeProfileKey,
    val startsFreshConversation: Boolean,
) {
    val id: String get() = alias
    /** One line of a sherpa-onnx keywords file: the tokens, then `@alias`. */
    val keywordLine: String get() = tokens.joinToString(" ") + " @" + alias
}

sealed class WakePhraseValidationError(message: String) : Exception(message) {
    class EmptyPhrase : WakePhraseValidationError(L10n.tr("Enter a wake phrase."))
    class MissingGatewayOrProfile : WakePhraseValidationError(L10n.tr("Wake phrases must belong to a gateway and profile."))
    class DuplicatePhrase(val phrase: String) : WakePhraseValidationError(L10n.tr("The wake phrase ‘%s’ is already enabled.", phrase))
    class UnsupportedEnglishWord(val word: String) : WakePhraseValidationError(L10n.tr("‘%s’ is not available in the English pronunciation lexicon.", word))
    class UnsupportedCharacter(val character: String) : WakePhraseValidationError(L10n.tr("‘%s’ is not supported by the bundled wake model.", character))
    class MissingChinesePronunciation(val character: String) : WakePhraseValidationError(L10n.tr("No pinyin token is available for ‘%s’.", character))

    override fun equals(other: Any?) = other is WakePhraseValidationError && other::class == this::class && other.message == message
    override fun hashCode() = message.hashCode()
}

/** Pronunciations by lowercased English word; pinyin tokens by Chinese character (code point). */
class WakeTokenizationTables(englishPronunciations: Map<String, List<String>>, val chinesePinyinTokens: Map<Int, String>) {
    val englishPronunciations: Map<String, List<String>> = englishPronunciations.mapKeys { it.key.lowercase() }

    companion object {
        val testFixtures = WakeTokenizationTables(
            englishPronunciations = mapOf(
                "hey" to listOf("HH", "EY"), "conduit" to listOf("K", "AA", "N", "D", "UW", "IH", "T"),
                "hermes" to listOf("HH", "ER", "M", "IY", "Z"), "talk" to listOf("T", "AO", "K"),
            ),
            chinesePinyinTokens = mapOf('小'.code to "xiao3", '爱'.code to "ai4", '同'.code to "tong2", '学'.code to "xue2", '你'.code to "ni3", '好'.code to "hao3"),
        )

        val unavailableProductionTables = WakeTokenizationTables(emptyMap(), emptyMap())

        /**
         * The production tables from the downloaded pack's `en.phone`
         * ("WORD PH1 PH2 …" per line; the first pronunciation wins). The
         * pack carries no character-to-pinyin table, so Chinese phrases
         * report their first character as missing.
         */
        fun fromLexicon(lexicon: Sequence<String>): WakeTokenizationTables {
            val english = HashMap<String, List<String>>()
            for (line in lexicon) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                english.putIfAbsent(parts[0].lowercase(), parts.drop(1))
            }
            return WakeTokenizationTables(english, emptyMap())
        }
    }
}

class WakePhraseCompiler(private val tables: WakeTokenizationTables) {
    fun compile(bindings: List<WakePhraseBinding>): List<CompiledWakePhrase> {
        val seen = mutableSetOf<String>()
        return bindings.map { binding ->
            if (binding.key.gatewayID.isEmpty() || binding.key.profileID.isEmpty()) throw WakePhraseValidationError.MissingGatewayOrProfile()
            val phrase = binding.normalizedPhrase
            if (phrase.isEmpty()) throw WakePhraseValidationError.EmptyPhrase()
            if (!seen.add(phrase)) throw WakePhraseValidationError.DuplicatePhrase(phrase)
            CompiledWakePhrase(phrase, tokens(phrase), stableAlias(binding.key, phrase), binding.key, binding.startsFreshConversation)
        }
    }

    fun tokens(phrase: String): List<String> {
        val output = mutableListOf<String>()
        val english = StringBuilder()
        fun appendEnglishWord() {
            if (english.isEmpty()) return
            val word = english.toString()
            english.clear()
            val pronunciation = tables.englishPronunciations[word.lowercase()]
            if (pronunciation.isNullOrEmpty()) throw WakePhraseValidationError.UnsupportedEnglishWord(word)
            output += pronunciation
        }
        var index = 0
        while (index < phrase.length) {
            val cp = phrase.codePointAt(index)
            index += Character.charCount(cp)
            if (cp < 0x80 && (Character.isLetter(cp) || cp == '\''.code)) {
                english.appendCodePoint(cp)
                continue
            }
            appendEnglishWord()
            if (Character.isWhitespace(cp)) continue
            val text = String(Character.toChars(cp))
            if (!Character.isIdeographic(cp)) throw WakePhraseValidationError.UnsupportedCharacter(text)
            val pinyin = tables.chinesePinyinTokens[cp]
            if (pinyin.isNullOrEmpty()) throw WakePhraseValidationError.MissingChinesePronunciation(text)
            output += pinyin
        }
        appendEnglishWord()
        if (output.isEmpty()) throw WakePhraseValidationError.EmptyPhrase()
        return output
    }

    companion object {
        fun normalize(phrase: String): String = phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

        /** FNV-1a 64 over "gateway|profile|phrase", as iOS: "conduit-<hex>". */
        fun stableAlias(key: WakeProfileKey, phrase: String): String {
            val source = "${key.gatewayID}|${key.profileID}|${normalize(phrase)}"
            var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (byte in source.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            return "conduit-" + java.lang.Long.toUnsignedString(hash, 16)
        }
    }
}

// endregion

// region Matcher

/**
 * Finds a bound wake phrase inside a running speech transcript. Latin-script
 * phrases match as whole words in order ("hey fam" matches "ok hey fam what's
 * up" but never "hey family"); phrases without word spacing (Chinese,
 * Japanese) match as a contiguous run of characters.
 */
object WakePhraseMatcher {
    /** At least two words (or two characters for scripts without word spacing). */
    fun isUsable(phrase: String): Boolean {
        val words = tokens(phrase)
        if (words.isEmpty()) return false
        if (usesWordSpacing(words)) return words.size >= 2
        return words.joinToString("").codePointCount() >= 2
    }

    fun match(transcript: String, bindings: List<WakePhraseBinding>): WakePhraseBinding? {
        val transcriptWords = tokens(transcript)
        if (transcriptWords.isEmpty()) return null
        val compactTranscript = transcriptWords.joinToString("")
        var best: Pair<WakePhraseBinding, Int>? = null
        for (binding in bindings) {
            if (!isUsable(binding.phrase)) continue
            val phraseWords = tokens(binding.phrase)
            val matched: Boolean
            val length: Int
            if (usesWordSpacing(phraseWords)) {
                matched = containsRun(phraseWords, transcriptWords)
                length = phraseWords.size
            } else {
                val compactPhrase = phraseWords.joinToString("")
                matched = compactTranscript.contains(compactPhrase)
                length = compactPhrase.codePointCount()
            }
            if (!matched) continue
            // The most specific phrase wins ("hey fam" vs "hey fam work").
            if (best == null || length > best.second) best = binding to length
        }
        return best?.first
    }

    /** Lowercased, accent- and width-folded runs of letters and digits. */
    fun tokens(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    private fun String.codePointCount() = codePointCount(0, length)

    private fun usesWordSpacing(words: List<String>) = words.all { word ->
        word.codePoints().allMatch { cp -> cp !in 0x3040..0x30FF && cp !in 0x3400..0x9FFF && cp !in 0xAC00..0xD7AF }
    }

    private fun containsRun(needle: List<String>, haystack: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { start -> haystack.subList(start, start + needle.size) == needle }
    }
}

// endregion

// region Configuration

data class WakeProfilePreferences(val enabledPhrases: List<String> = emptyList(), val startsFreshConversation: Boolean = true)

/**
 * Device-local wake configuration: phrases per dashboard and profile, never
 * Hermes profile configuration. Stored as iOS stores it
 * (`conduit.wakeConfiguration.v1`: a Swift dictionary with struct keys,
 * i.e. an array alternating key and value).
 */
data class WakeConfiguration(
    val values: Map<WakeProfileKey, WakeProfilePreferences> = emptyMap(),
    /** iOS "Listen on CarPlay"; kept for parity, unused on Android. */
    val listensOnCarPlay: Boolean = true,
    /** Keep listening while another app plays audio. Off by default. */
    val listensOverOtherAudio: Boolean = false,
    /** Android: keep listening with Conduit in the background (a microphone foreground service). */
    val listensInBackground: Boolean = false,
) {
    fun preferences(key: WakeProfileKey) = values[key] ?: WakeProfilePreferences()

    fun saving(preferences: WakeProfilePreferences, key: WakeProfileKey) =
        copy(values = values + (key to WakeProfilePreferences(uniquePhrases(preferences.enabledPhrases), preferences.startsFreshConversation)))

    fun removing(key: WakeProfileKey) = copy(values = values - key)

    fun enabledBindings(): List<WakePhraseBinding> =
        values.flatMap { (key, prefs) -> prefs.enabledPhrases.map { WakePhraseBinding(key, it, prefs.startsFreshConversation) } }
            .sortedBy { it.id }

    fun encodeValues(): String = buildJsonArray {
        for ((key, prefs) in values) {
            add(buildJsonObject { put("gatewayID", key.gatewayID); put("profileID", key.profileID) })
            add(buildJsonObject {
                put("enabledPhrases", JsonArray(prefs.enabledPhrases.map { JsonPrimitive(it) }))
                put("startsFreshConversation", prefs.startsFreshConversation)
            })
        }
    }.toString()

    companion object {
        const val STORAGE_KEY = "conduit.wakeConfiguration.v1"
        const val CAR_PLAY_KEY = "$STORAGE_KEY.listensOnCarPlay"
        const val OTHER_AUDIO_KEY = "$STORAGE_KEY.listensOverOtherAudio"
        const val BACKGROUND_KEY = "$STORAGE_KEY.listensInBackground"

        fun decodeValues(text: String?): Map<WakeProfileKey, WakeProfilePreferences> {
            val array = runCatching { ConduitJson.parseToJsonElement(text ?: return emptyMap()) }.getOrNull() as? JsonArray ?: return emptyMap()
            val out = LinkedHashMap<WakeProfileKey, WakeProfilePreferences>()
            var i = 0
            while (i + 1 < array.size) {
                val key = array[i] as? JsonObject
                val value = array[i + 1] as? JsonObject
                i += 2
                if (key == null || value == null) continue
                val gateway = key["gatewayID"].str ?: continue
                val profile = key["profileID"].str ?: continue
                val phrases = value["enabledPhrases"].arr?.mapNotNull { it.str } ?: continue
                out[WakeProfileKey.of(gateway, profile)] = WakeProfilePreferences(phrases, value["startsFreshConversation"].bool ?: true)
            }
            return out
        }

        fun uniquePhrases(phrases: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            return phrases.mapNotNull { phrase ->
                val normalized = WakePhraseCompiler.normalize(phrase)
                if (normalized.isEmpty() || !seen.add(normalized)) null else normalized
            }
        }
    }
}

// endregion

// region Lifecycle

data class WakeLifecycleSnapshot(
    /** Conduit on screen, or background listening allowed by the user and running in its service. */
    val isForegroundActive: Boolean,
    val isAuthenticated: Boolean,
    val isGatewayConnected: Boolean,
    val microphonePermitted: Boolean,
    /** No voice surface, no voice launch in flight, no other audio owner (Read Aloud, dictation). */
    val isVoiceIdle: Boolean,
    /** At least one usable wake phrase on the current dashboard. */
    val hasWakePhrases: Boolean,
    val isRouteSuitable: Boolean = true,
    /** No other app plays audio, or the user keeps wake listening over it. */
    val otherAudioAllowsListening: Boolean = true,
) {
    val canArmIgnoringOtherAudio: Boolean
        get() = isForegroundActive && isAuthenticated && isGatewayConnected && microphonePermitted && isVoiceIdle && hasWakePhrases && isRouteSuitable
    val canArm: Boolean get() = canArmIgnoringOtherAudio && otherAudioAllowsListening
    val isPausedForOtherAudio: Boolean get() = canArmIgnoringOtherAudio && !otherAudioAllowsListening
}

interface WakeWordService {
    val isArmed: Boolean
    fun arm()
    fun disarm()
}

/** Arms the listener when the snapshot allows it, disarms it otherwise (iOS WakeLifecycleCoordinator). */
class WakeLifecycleCoordinator(private val service: WakeWordService, private val describe: (Throwable) -> String = { it.message ?: it.toString() }) {
    var lastFailureReason: String? = null
        private set

    val isArmed get() = service.isArmed

    fun update(snapshot: WakeLifecycleSnapshot) {
        if (!snapshot.canArm) {
            service.disarm()
            lastFailureReason = null
            return
        }
        if (service.isArmed) return
        try {
            service.arm()
            lastFailureReason = null
        } catch (e: Exception) {
            service.disarm()
            lastFailureReason = describe(e)
        }
    }

    fun disarmImmediately() {
        service.disarm()
        lastFailureReason = null
    }
}

// endregion
