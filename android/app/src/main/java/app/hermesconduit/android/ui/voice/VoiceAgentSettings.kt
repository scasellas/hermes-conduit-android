package app.hermesconduit.android.ui.voice

import app.hermesconduit.android.core.live.GeminiLiveSearchMode
import app.hermesconduit.android.core.live.LiveVoiceAnswerLength
import app.hermesconduit.android.core.live.LiveVoiceStyle
import app.hermesconduit.android.core.live.LiveVoiceTone
import app.hermesconduit.android.core.live.LiveVoiceVoices
import app.hermesconduit.android.model.VoiceProfilePreferences
import app.hermesconduit.android.ui.agentsettings.AgentSettingDescriptor
import app.hermesconduit.android.ui.agentsettings.AgentSettingsSync
import app.hermesconduit.android.ui.agentsettings.AgentValue

/**
 * The voice settings Hermes may read and propose changes to (iOS
 * AppState+AgentSettings' voice part), for the active profile. Keys,
 * descriptions and tiers match iOS; written for Hermes, so not localized.
 */
object VoiceAgentSettings {
    fun register(hub: VoiceHub) {
        val descriptors = descriptors(hub)
        synchronized(AgentSettingsSync.catalog) {
            val keys = descriptors.map { it.key }.toSet()
            AgentSettingsSync.catalog.removeAll { it.key in keys }
            AgentSettingsSync.catalog.addAll(descriptors)
        }
    }

    private fun bool(key: String, section: String, title: String, description: String, tier: String, read: () -> Boolean, write: (Boolean) -> Unit) =
        AgentSettingDescriptor(key, section, title, description, null, tier, { AgentValue.B(read()) }, { (it as? AgentValue.B)?.let { v -> write(v.value) } })

    private fun choice(key: String, section: String, title: String, description: String, tier: String, choices: List<Pair<String, String>>, read: () -> String, write: (String) -> Unit) =
        AgentSettingDescriptor(key, section, title, description, choices, tier, { AgentValue.S(read()) }, { (it as? AgentValue.S)?.let { v -> write(v.value) } })

    fun descriptors(hub: VoiceHub): List<AgentSettingDescriptor> {
        val prefs: () -> VoiceProfilePreferences = { hub.preferences() }
        fun update(change: (VoiceProfilePreferences) -> VoiceProfilePreferences) {
            if (hub.hermes.isSignedIn) hub.updatePreferences(change)
        }
        fun style() = LiveVoiceStyle.of(prefs())
        fun updateStyle(change: (LiveVoiceStyle) -> LiveVoiceStyle) = update { LiveVoiceStyle.stored(it, change(LiveVoiceStyle.of(it))) }
        val list = mutableListOf<AgentSettingDescriptor>()
        list += choice(
            "voice.mode", "Voice mode", "Voice mode",
            "Which voice engine voice conversations use. classic: Hermes' own speech to text and voice (works with any server). gemini: Gemini Live, needs a Gemini key on the host. gpt: GPT-Live on the host's ChatGPT sign-in. grok: Grok Live on the host's SuperGrok sign-in. Live modes need the notifier plugin.",
            "ask", listOf("classic" to "Classic", "gemini" to "Gemini Live", "gpt" to "GPT-Live", "grok" to "Grok Live"),
            read = {
                val p = prefs()
                when {
                    p.geminiLiveEnabled -> "gemini"
                    p.gptLiveEnabled -> "gpt"
                    p.grokLiveEnabled -> "grok"
                    else -> "classic"
                }
            },
            write = { value ->
                hub.liveCalls.close()
                update { it.copy(geminiLiveEnabled = value == "gemini", gptLiveEnabled = value == "gpt", grokLiveEnabled = value == "grok") }
            },
        )
        list += choice(
            "voice.live.tone", "Live voice", "Tone", "How every live voice mode sounds. default keeps the voice model's own tone.", "small",
            listOf("default" to "Model's own") + LiveVoiceTone.entries.map { it.raw to it.raw.replaceFirstChar(Char::uppercase) },
            read = { style().tone?.raw ?: "default" },
            write = { value -> updateStyle { it.copy(tone = LiveVoiceTone.of(value)) } },
        )
        list += choice(
            "voice.live.answer_length", "Live voice", "Answer length",
            "How much live models say when asked something: concise (the point), standard (the answer with the details that make it useful), detailed.", "small",
            LiveVoiceAnswerLength.entries.map { it.raw to it.raw.replaceFirstChar(Char::uppercase) },
            read = { style().answerLength.raw },
            write = { value -> updateStyle { it.copy(answerLength = LiveVoiceAnswerLength.of(value) ?: LiveVoiceAnswerLength.STANDARD) } },
        )
        list += bool(
            "voice.live.backchannels", "Live voice", "Backchannels", "Whether live models say short \"mm-hmm\" sounds while the user talks.", "small",
            read = { style().backchannels }, write = { on -> updateStyle { it.copy(backchannels = on) } },
        )
        list += bool(
            "voice.live.ask_before_sending", "Live voice", "Ask before sending to Hermes",
            "Live calls start by reading each request back and waiting for the user's OK before it goes to Hermes.", "ask",
            read = { style().asksBeforeSending }, write = { on -> updateStyle { it.copy(asksBeforeSending = on) } },
        )
        list += choice(
            "voice.gemini.voice", "Gemini Live", "Gemini Live voice", "The voice Gemini Live speaks with. default is Gemini's own default voice.", "small",
            listOf("default" to "Gemini's default") + LiveVoiceVoices.gemini.map { it.name to "${it.name} (${it.style})" },
            read = { prefs().geminiLiveVoice ?: "default" },
            write = { value -> update { it.copy(geminiLiveVoice = value.takeIf { v -> v != "default" }) } },
        )
        list += choice(
            "voice.gemini.search", "Gemini Live", "Gemini Live lookups",
            "Where Gemini Live searches for quick facts: automatic (Hermes' web search when the host has one, else Google), hermes, google, or off.", "ask",
            listOf("automatic" to "Automatic", "hermes" to "Hermes web search", "google" to "Google Search", "off" to "Off"),
            read = { GeminiLiveSearchMode.of(prefs().geminiLiveSearch).raw },
            write = { value -> update { it.copy(geminiLiveSearch = value.takeIf { v -> v != "automatic" }) } },
        )
        list += choice(
            "voice.gpt.voice", "GPT-Live", "GPT-Live voice",
            "The voice GPT-Live speaks with. default is the host's configured voice (voice.gpt_live in Hermes config, cove unless changed).", "small",
            listOf("default" to "Host's configured voice") + LiveVoiceVoices.gptLive.map { it.name to "${it.name} (${it.style})" },
            read = { prefs().gptLiveVoice ?: "default" },
            write = { value -> update { it.copy(gptLiveVoice = value.takeIf { v -> v != "default" }) } },
        )
        for ((engine, title, company) in listOf(Triple("gemini", "Gemini Live", "Google"), Triple("gpt", "GPT-Live", "OpenAI"), Triple("grok", "Grok Live", "xAI"))) {
            list += bool(
                "voice.$engine.memory", title, "$title memory", "Gives $title what Hermes remembers about the user. This sends that memory to $company.", "ask",
                read = {
                    val p = prefs()
                    when (engine) { "gemini" -> p.geminiLiveMemory; "gpt" -> p.gptLiveMemory; else -> p.grokLiveMemory } == true
                },
                write = { on ->
                    val stored = if (on) true else null
                    update { when (engine) { "gemini" -> it.copy(geminiLiveMemory = stored); "gpt" -> it.copy(gptLiveMemory = stored); else -> it.copy(grokLiveMemory = stored) } }
                },
            )
            list += bool(
                "voice.$engine.personality", title, "$title personality", "Makes $title speak as this Hermes profile's persona (SOUL.md). This sends SOUL.md to $company.", "ask",
                read = {
                    val p = prefs()
                    when (engine) { "gemini" -> p.geminiLivePersonality; "gpt" -> p.gptLivePersonality; else -> p.grokLivePersonality } == true
                },
                write = { on ->
                    val stored = if (on) true else null
                    update { when (engine) { "gemini" -> it.copy(geminiLivePersonality = stored); "gpt" -> it.copy(gptLivePersonality = stored); else -> it.copy(grokLivePersonality = stored) } }
                },
            )
        }
        list += bool(
            "voice.live.save_calls", "Live voice", "Save live calls",
            "Saves each live voice call (Gemini Live, GPT-Live, Grok Live) to Hermes' chat history. Off means calls aren't kept.", "ask",
            read = { prefs().saveVoiceCalls != false }, write = { on -> update { it.copy(saveVoiceCalls = if (on) null else false) } },
        )
        list += bool(
            "voice.live.speaker_talk_over", "Live voice", "Talk over Hermes on the speaker",
            "Keeps the microphone open while a live model speaks on the loudspeaker or in the car, so the user can interrupt (echo cancelling, experimental).", "ask",
            read = { prefs().liveVoiceSpeakerBargeIn == true }, write = { on -> update { it.copy(liveVoiceSpeakerBargeIn = if (on) true else null) } },
        )
        list += bool(
            "voice.continuous_conversation", "Conversation", "Continuous Conversation",
            "In Classic voice, listens again after each reply instead of waiting for a tap.", "ask",
            read = { prefs().continuousConversation }, write = { on -> update { it.copy(continuousConversation = on) } },
        )
        list += bool(
            "voice.keep_listening_when_locked", "Conversation", "Keep listening when locked",
            "Keeps a Classic voice conversation listening with the phone locked or Conduit in the background.", "ask",
            read = { prefs().keepListeningWhenLocked == true }, write = { on -> update { it.copy(keepListeningWhenLocked = on) } },
        )
        return list
    }
}
