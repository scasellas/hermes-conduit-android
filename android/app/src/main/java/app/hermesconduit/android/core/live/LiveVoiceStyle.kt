package app.hermesconduit.android.core.live

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.VoiceProfilePreferences

/**
 * How live voice calls (Gemini Live, GPT-Live, Grok Live) sound, chosen per
 * profile (#290): a tone, whether the model backchannels ("mm-hmm") while
 * the user talks, an optional greeting when a call connects, and how much
 * the model says. An unset tone or backchannel changes nothing: the host's
 * own persona decides. Answer length always has a rule, Default unless the
 * user picks another.
 */
enum class LiveVoiceTone(val raw: String) {
    RELAXED("relaxed"), NEUTRAL("neutral"), PROFESSIONAL("professional");

    val label: String
        get() = when (this) {
            RELAXED -> L10n.tr("Relaxed")
            NEUTRAL -> L10n.tr("Neutral")
            PROFESSIONAL -> L10n.tr("Professional")
        }

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * How much a live model says when the user asks something. Told only to keep
 * replies short, voice models answered in a line and left the user asking
 * follow-ups, so Default asks for the details; Concise is for users who want
 * just the point.
 */
enum class LiveVoiceAnswerLength(val raw: String) {
    CONCISE("concise"), STANDARD("standard"), DETAILED("detailed");

    val label: String
        get() = when (this) {
            CONCISE -> L10n.tr("Concise")
            STANDARD -> L10n.tr("Default")
            DETAILED -> L10n.tr("Detailed")
        }

    /**
     * The answer-length rule in the live model's instructions. Written for the
     * model, not shown as UI copy, so not localized. A call with no read-backs
     * leaves out the read-back exception.
     */
    fun instructions(readsBack: Boolean): String {
        val lead = when (this) {
            CONCISE -> "Keep answers short and to the point: lead with the answer itself and the one or two specifics that make it useful (the number, name or step that matters), usually one to three sentences. Leave out background and caveats unless they change the answer; the user will ask if they want more. Still give a real answer, never a vague headline."
            STANDARD -> "Say enough to be useful. Answer a real question fully the first time: the answer, then the details that make it useful (the reasons, numbers, names, steps or options that matter, and any caveat), usually several sentences and more when the topic needs it. Don't leave the user asking follow-up questions to get the substance, and don't stop at a headline or end on an offer to say more instead of saying it."
            DETAILED -> "Be thorough. Answer a real question in depth the first time: the answer, then the reasoning behind it, the numbers, names, steps and options that matter, an example where it helps, and the caveats worth knowing, often several paragraphs of speech. Don't leave the user asking follow-up questions to get the full picture, and don't end on an offer to say more instead of saying it."
        }
        val scope = if (readsBack) {
            " This goes for everything you say, Hermes' results included, except a read-back: when the user asks to hear a reply again or in full and Conduit gives it to you to read, read all of it word for word, however long."
        } else {
            " This goes for everything you say, Hermes' results included."
        }
        val speech = scope + " It is still speech: say it in natural spoken sentences, not lists, headings or Markdown, and never read out URLs. When the detail is on the user's screen, that takes the place of a long spoken answer."
        return if (this == CONCISE) lead + speech else lead + speech + " Keep it short only for greetings, small talk, acknowledgements and confirmations."
    }

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

data class LiveVoiceStyle(
    /** Null keeps the voice model's own tone. */
    val tone: LiveVoiceTone? = null,
    val backchannels: Boolean = true,
    /** Null: the call stays silent until the user speaks. Empty: a short greeting in the model's own words. Text: that greeting. */
    val greeting: String? = null,
    val answerLength: LiveVoiceAnswerLength = LiveVoiceAnswerLength.STANDARD,
    /** Whether a call starts holding each request for the user's OK (#451). */
    val asksBeforeSending: Boolean = false,
) {
    /** Added after the persona, so it wins over the tone and backchannels the persona asks for. Not localized. */
    val instructions: String
        get() {
            val lines = mutableListOf<String>()
            when (tone) {
                LiveVoiceTone.RELAXED -> lines += "Tone: relaxed and warm, at an easy, unhurried pace."
                LiveVoiceTone.NEUTRAL -> lines += "Tone: neutral and matter-of-fact, at a normal conversational pace. Skip filler and pleasantries."
                LiveVoiceTone.PROFESSIONAL -> lines += "Tone: professional, at a brisk pace. No small talk, filler or pleasantries: spend the words on substance."
                null -> Unit
            }
            if (!backchannels) {
                lines += "Backchannels: none. Don't say \"mm-hmm\", \"right\", \"I see\" or similar while the user talks or before you answer."
            }
            if (lines.isEmpty()) return ""
            return "\n\n[Voice style the user chose in Conduit. It replaces any tone, pace or backchannel guidance above.]\n" + lines.joinToString("\n")
        }

    /** The turn that makes the model greet the user when the call connects. Not UI copy. */
    val openingPrompt: String?
        get() {
            val greeting = greeting ?: return null
            val say = if (greeting.isEmpty()) "" else " Say: \"$greeting\""
            return "[The call just connected. Greet the user in one short sentence so they know you're there and listening.$say Then wait for them.]"
        }

    companion object {
        const val MAXIMUM_GREETING_CHARACTERS = 200

        /** The user's greeting on one line, without double quotes (it is quoted to the model), clipped. */
        fun cleanedGreeting(text: String): String =
            text.replace("\"", "'").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(MAXIMUM_GREETING_CHARACTERS)

        /** Whether the call starts asking first (#451), after the rules that say how it works. Not UI copy. */
        fun askFirstInstructions(on: Boolean): String =
            if (on) "\n\nAsking first is on for this call: a new request goes to Hermes only once the user OKs it."
            else "\n\nAsking first is off for this call: new requests go to Hermes straight away."

        fun of(preferences: VoiceProfilePreferences) = LiveVoiceStyle(
            tone = LiveVoiceTone.of(preferences.liveVoiceTone),
            backchannels = preferences.liveVoiceBackchannels ?: true,
            greeting = preferences.liveVoiceGreeting,
            answerLength = LiveVoiceAnswerLength.of(preferences.liveVoiceAnswerLength) ?: LiveVoiceAnswerLength.STANDARD,
            asksBeforeSending = preferences.liveVoiceAskBeforeSending ?: false,
        )

        /** Stores `style` the way iOS `setLiveVoiceStyle` does: defaults as null. */
        fun stored(preferences: VoiceProfilePreferences, style: LiveVoiceStyle) = preferences.copy(
            liveVoiceTone = style.tone?.raw,
            liveVoiceBackchannels = if (style.backchannels) null else false,
            liveVoiceGreeting = style.greeting?.let(::cleanedGreeting),
            liveVoiceAnswerLength = if (style.answerLength == LiveVoiceAnswerLength.STANDARD) null else style.answerLength.raw,
            liveVoiceAskBeforeSending = if (style.asksBeforeSending) true else null,
        )
    }
}

/** The user's choice for Gemini Live's quick lookups (weather, news, facts). */
enum class GeminiLiveSearchMode(val raw: String) {
    /** The Hermes host's own web search when it has one, else Google Search. */
    AUTOMATIC("automatic"),
    HERMES("hermes"),
    GOOGLE("google"),
    OFF("off");

    fun resolved(hermesAvailable: Boolean): GeminiLiveSearchSource = when (this) {
        AUTOMATIC -> if (hermesAvailable) GeminiLiveSearchSource.HERMES else GeminiLiveSearchSource.GOOGLE
        HERMES -> GeminiLiveSearchSource.HERMES
        GOOGLE -> GeminiLiveSearchSource.GOOGLE
        OFF -> GeminiLiveSearchSource.NONE
    }

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: AUTOMATIC
    }
}

/** What a live session actually searches with. */
enum class GeminiLiveSearchSource { HERMES, GOOGLE, GROK, NONE }

/** A prebuilt voice: its API name and how it sounds. */
data class LiveVoiceChoice(val name: String, val style: String) {
    /** "Cove · Composed". */
    val label: String get() = name.replaceFirstChar { it.uppercase() } + " · " + style
}

object LiveVoiceVoices {
    /** Google's prebuilt voices for native-audio models, in Google's order. */
    val gemini: List<LiveVoiceChoice>
        get() = listOf(
            "Zephyr" to "Bright", "Puck" to "Upbeat", "Charon" to "Informative", "Kore" to "Firm", "Fenrir" to "Excitable",
            "Leda" to "Youthful", "Orus" to "Firm", "Aoede" to "Breezy", "Callirrhoe" to "Easy-going", "Autonoe" to "Bright",
            "Enceladus" to "Breathy", "Iapetus" to "Clear", "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Despina" to "Smooth",
            "Erinome" to "Clear", "Algenib" to "Gravelly", "Rasalgethi" to "Informative", "Laomedeia" to "Upbeat", "Achernar" to "Soft",
            "Alnilam" to "Firm", "Schedar" to "Even", "Gacrux" to "Mature", "Pulcherrima" to "Forward", "Achird" to "Friendly",
            "Zubenelgenubi" to "Casual", "Vindemiatrix" to "Gentle", "Sadachbia" to "Lively", "Sadaltager" to "Knowledgeable", "Sulafat" to "Warm",
        ).map { (name, style) -> LiveVoiceChoice(name, L10n.tr(style)) }

    /** ChatGPT's voices; a voice the account doesn't have is refused by GPT-Live when the call starts. */
    val gptLive: List<LiveVoiceChoice>
        get() = listOf(
            "arbor" to "Easy-going", "breeze" to "Animated", "cove" to "Composed", "ember" to "Confident", "juniper" to "Upbeat",
            "maple" to "Cheerful", "sol" to "Savvy", "spruce" to "Calm", "vale" to "Bright",
        ).map { (name, style) -> LiveVoiceChoice(name, L10n.tr(style)) }

    /** The public GPT-Live API's voices (an API-key call), as Hermes lists them. */
    val gptLiveApiNames = listOf(
        "marin", "cedar", "quartz", "ripple", "vesper", "willow", "stone",
        "gleam", "meridian", "bossa", "tempo", "beacon", "delta", "cinder",
    )
}

/** How long an API-key GPT-Live call may run (#559). */
object GPTLiveAPICallLimit {
    const val DEFAULT_MINUTES = 20
    const val MAXIMUM_MINUTES = 24 * 60
    /** The choices Settings offers; 0 is no limit. */
    val choices = listOf(5, 10, 20, 30, 60, 0)

    fun minutes(stored: Int?): Int = if (stored == null || stored !in 0..MAXIMUM_MINUTES) DEFAULT_MINUTES else stored

    /** Milliseconds, or null for no limit. */
    fun durationMs(stored: Int?): Long? = minutes(stored).let { if (it == 0) null else it * 60_000L }
}
