package app.hermesconduit.android.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.live.GPTLiveAPICallLimit
import app.hermesconduit.android.core.live.GPTLiveAvailability
import app.hermesconduit.android.core.live.GPTLiveBilling
import app.hermesconduit.android.core.live.GPTLiveClient
import app.hermesconduit.android.core.live.GeminiLiveAvailability
import app.hermesconduit.android.core.live.GeminiLiveSearchMode
import app.hermesconduit.android.core.live.GrokLiveAvailability
import app.hermesconduit.android.core.live.LiveVoiceAnswerLength
import app.hermesconduit.android.core.live.LiveVoiceStyle
import app.hermesconduit.android.core.live.LiveVoiceTone
import app.hermesconduit.android.core.live.LiveVoiceVoices
import app.hermesconduit.android.core.live.liveErrorMessage
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.ModelProvider
import app.hermesconduit.android.model.VoiceProfilePreferences
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The live mode's own settings under Voice mode (iOS GeminiLiveSettingsSection, GPTLiveSettingsSection, GrokLiveSettingsSection). */
@Composable
internal fun LiveVoiceModeSettings(vm: VoiceSettingsViewModel, prefs: VoiceProfilePreferences) {
    when {
        prefs.geminiLiveEnabled -> GeminiLiveSettings(vm, prefs)
        prefs.gptLiveEnabled -> GPTLiveSettings(vm, prefs)
        prefs.grokLiveEnabled -> GrokLiveSettings(vm, prefs)
    }
    if (prefs.geminiLiveEnabled || prefs.gptLiveEnabled || prefs.grokLiveEnabled) LiveCallStyleSection(vm, prefs)
}

/** "Check …": the host's answer for this mode, or why it couldn't be asked. */
@Composable
private fun AvailabilityRow(checkTitle: String, check: suspend () -> Pair<Boolean, String?>) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(status ?: t("Not checked yet"), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled = !checking, onClick = {
            scope.launch {
                checking = true
                status = try { check().second } catch (e: Exception) { liveErrorMessage(e) }
                checking = false
            }
        }) { Text(if (checking) t("Checking…") else checkTitle) }
    }
}

@Composable
private fun SaveCallsToggle(vm: VoiceSettingsViewModel, prefs: VoiceProfilePreferences) {
    SettingsToggle(t("Save calls to Sessions"), prefs.saveVoiceCalls != false, { on -> vm.updatePreferences { it.copy(saveVoiceCalls = if (on) null else false) } })
    Footnote(t("Each call's transcript is saved to your Hermes server's history and shows under Voice in Sessions, where you can read it or resume the call. Needs an up-to-date Hermes notifier plugin. Shared by every live voice mode. Applies to the next conversation."))
}

@Composable
private fun GeminiLiveSettings(vm: VoiceSettingsViewModel, prefs: VoiceProfilePreferences) {
    val calls = vm.hub.liveCalls
    SettingsSection(t("Gemini Live")) {
        Footnote(t("Talk with Gemini Live instead of the Hermes speech pipeline. Hermes still does the work: Gemini starts background jobs on this profile and tells you their results. The API key stays on your Hermes server. Approvals are never given by voice."))
        AvailabilityRow(t("Check Gemini Live")) {
            when (val status = calls.tokenClient.availability()) {
                is GeminiLiveAvailability.Available -> true to L10n.tr("Available (%s)", status.model)
                else -> false to status.userFacingReason
            }
        }
        val search = GeminiLiveSearchMode.of(prefs.geminiLiveSearch)
        val searchChoices = listOf(
            GeminiLiveSearchMode.AUTOMATIC to t("Automatic"),
            GeminiLiveSearchMode.HERMES to t("Hermes web search"),
            GeminiLiveSearchMode.GOOGLE to t("Google Search"),
            GeminiLiveSearchMode.OFF to t("Off"),
        )
        ChoiceRow(t("Web lookups"), searchChoices.first { it.first == search }.second, searchChoices.map { it.first.raw to it.second }) { raw ->
            vm.updatePreferences { it.copy(geminiLiveSearch = if (raw == GeminiLiveSearchMode.AUTOMATIC.raw) null else raw) }
        }
        Footnote(t("How Gemini answers quick questions like weather or news. Hermes web search uses the search your Hermes server is set up with (SearXNG, Firecrawl…). Google Search has its own quota on your Gemini key. Automatic uses Hermes when it has a search set up, otherwise Google. Applies to the next conversation."))
        val voices = listOf("" to t("Gemini default")) + LiveVoiceVoices.gemini.map { it.name to it.label }
        ChoiceRow(t("Voice"), voices.firstOrNull { it.first == prefs.geminiLiveVoice.orEmpty() }?.second ?: prefs.geminiLiveVoice.orEmpty(), voices) { name ->
            vm.updatePreferences { it.copy(geminiLiveVoice = name.ifEmpty { null }) }
        }
        Footnote(t("The voice Gemini speaks with. Applies to the next conversation."))
        SettingsToggle(t("Use Hermes memory"), prefs.geminiLiveMemory == true, { on -> vm.updatePreferences { it.copy(geminiLiveMemory = if (on) true else null) } })
        Footnote(t("Gemini gets what your Hermes agent remembers about you, from whichever memory Hermes is set up with, and can search it for more. That memory is sent to Google with the conversation. Applies to the next conversation."))
        SettingsToggle(t("Use Hermes personality"), prefs.geminiLivePersonality == true, { on -> vm.updatePreferences { it.copy(geminiLivePersonality = if (on) true else null) } })
        Footnote(t("Gemini talks with the personality in this profile's SOUL.md, without reading out actions or emoji. SOUL.md is sent to Google with the conversation. Applies to the next conversation."))
        SaveCallsToggle(vm, prefs)
    }
}

@Composable
private fun GPTLiveSettings(vm: VoiceSettingsViewModel, prefs: VoiceProfilePreferences) {
    val calls = vm.hub.liveCalls
    val billing = if (prefs.gptLiveBilling == GPTLiveBilling.API.raw) GPTLiveBilling.API else GPTLiveBilling.SUBSCRIPTION
    var confirmApi by remember { mutableStateOf(false) }
    SettingsSection(t("GPT-Live")) {
        Footnote(t("Talk with GPT-Live on the ChatGPT subscription your Hermes server is signed in to, instead of the Hermes speech pipeline. Hermes still does the work: GPT-Live hands requests to background jobs on this profile. The sign-in stays on your Hermes server, and it never falls back to API billing. Turning this on turns Gemini Live and Grok Live off."))
        AvailabilityRow(t("Check GPT-Live")) {
            when (val status = calls.gptClient.availability(billing)) {
                is GPTLiveAvailability.Available -> true to (status.voice?.let { L10n.tr("Available (%s, voice %s)", status.model, it) } ?: L10n.tr("Available (%s)", status.model))
                else -> false to status.userFacingReason
            }
        }
        ChoiceRow(t("Billing"), GPTLiveClient.badge(billing), GPTLiveBilling.entries.map { it.raw to GPTLiveClient.badge(it) }) { raw ->
            if (raw == GPTLiveBilling.API.raw && billing != GPTLiveBilling.API) confirmApi = true
            else vm.updatePreferences { it.copy(gptLiveBilling = null) }
        }
        Footnote(
            if (billing == GPTLiveBilling.API) t("Bills your Hermes server's OpenAI API key per minute of call time, separately from any ChatGPT plan. The call screen shows which one a call is on.")
            else t("Bills the ChatGPT subscription your Hermes server is signed in to. A call never moves to the API key on its own."),
        )
        if (billing == GPTLiveBilling.API) {
            val apiVoices = listOf("" to t("Server default")) + LiveVoiceVoices.gptLiveApiNames.map { it to it.replaceFirstChar(Char::uppercase) }
            ChoiceRow(t("Voice"), apiVoices.firstOrNull { it.first == prefs.gptLiveAPIVoice.orEmpty() }?.second ?: prefs.gptLiveAPIVoice.orEmpty(), apiVoices) { name ->
                vm.updatePreferences { it.copy(gptLiveAPIVoice = name.ifEmpty { null }) }
            }
            Footnote(t("The voice GPT-Live speaks with on the API key. Server default uses voice.gpt_live.voice in the profile's config. Applies to the next conversation."))
            val minutes = GPTLiveAPICallLimit.minutes(prefs.gptLiveAPIMaxMinutes)
            val limits = (if (minutes in GPTLiveAPICallLimit.choices) GPTLiveAPICallLimit.choices else listOf(minutes) + GPTLiveAPICallLimit.choices)
                .map { it.toString() to limitTitle(it) }
            ChoiceRow(t("Time limit per call"), limitTitle(minutes), limits) { picked ->
                vm.updatePreferences { it.copy(gptLiveAPIMaxMinutes = picked.toInt().takeIf { value -> value != GPTLiveAPICallLimit.DEFAULT_MINUTES }) }
            }
            Footnote(t("An API-key call says goodbye and ends when it reaches this limit."))
            SettingsToggle(t("Calls from Hermes on the API key"), prefs.gptLiveAPIHermesCalls == true, { on -> vm.updatePreferences { it.copy(gptLiveAPIHermesCalls = if (on) true else null) } })
            Footnote(t("When Hermes calls you, the call uses the ChatGPT subscription unless this is on."))
        } else {
            val voices = listOf("" to t("Server default")) + LiveVoiceVoices.gptLive.map { it.name to it.label }
            ChoiceRow(t("Voice"), voices.firstOrNull { it.first == prefs.gptLiveVoice.orEmpty() }?.second ?: prefs.gptLiveVoice.orEmpty(), voices) { name ->
                vm.updatePreferences { it.copy(gptLiveVoice = name.ifEmpty { null }) }
            }
            Footnote(t("The voice GPT-Live speaks with. Server default uses the voice set on your Hermes server (voice.gpt_live in the profile's config). The model is set there too. Applies to the next conversation."))
        }
        SettingsToggle(t("Use Hermes memory"), prefs.gptLiveMemory == true, { on -> vm.updatePreferences { it.copy(gptLiveMemory = if (on) true else null) } })
        Footnote(t("GPT-Live gets what your Hermes agent remembers about you. That memory is sent to OpenAI with the conversation. Applies to the next conversation."))
        SettingsToggle(t("Use Hermes personality"), prefs.gptLivePersonality == true, { on -> vm.updatePreferences { it.copy(gptLivePersonality = if (on) true else null) } })
        Footnote(t("GPT-Live talks with the personality in this profile's SOUL.md. SOUL.md is sent to OpenAI with the conversation. Applies to the next conversation."))
        SaveCallsToggle(vm, prefs)
    }
    if (confirmApi) {
        AlertDialog(
            onDismissRequest = { confirmApi = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmApi = false
                    vm.updatePreferences { it.copy(gptLiveBilling = GPTLiveBilling.API.raw) }
                }) { Text(t("Use the API key")) }
            },
            dismissButton = { TextButton(onClick = { confirmApi = false }) { Text(t("Cancel")) } },
            text = { Text(t("GPT-Live calls will use your Hermes server's OpenAI API key, which OpenAI bills per minute of call time, separately from any ChatGPT plan. Your Hermes server has to allow it too (voice.gpt_live.conduit_api_billing in the profile's config).")) },
        )
    }
}

private fun limitTitle(minutes: Int): String = when {
    minutes <= 0 -> L10n.tr("No limit")
    minutes % 60 == 0 -> "${minutes / 60} h"
    minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
    else -> "$minutes min"
}

@Composable
private fun GrokLiveSettings(vm: VoiceSettingsViewModel, prefs: VoiceProfilePreferences) {
    val calls = vm.hub.liveCalls
    SettingsSection(t("Grok Live")) {
        Footnote(t("Talk with Grok Live, xAI's realtime voice model, instead of the Hermes speech pipeline. Hermes still does the work: Grok hands requests to background jobs on this profile. Your Hermes server connects to xAI with its SuperGrok sign-in (or its XAI_API_KEY), which stays on the server. Turning this on turns Gemini Live and GPT-Live off."))
        AvailabilityRow(t("Check Grok Live")) {
            when (val status = calls.grokClient.availability()) {
                is GrokLiveAvailability.Available -> {
                    val via = if (status.auth == "subscription") "SuperGrok" else "XAI_API_KEY"
                    true to (status.voice?.let { L10n.tr("Available (%s, voice %s, via %s)", status.model, it, via) } ?: L10n.tr("Available (%s, via %s)", status.model, via))
                }
                else -> false to status.userFacingReason
            }
        }
        Footnote(t("The voice and model are set on your Hermes server (voice.grok_live in the profile's config)."))
        SettingsToggle(t("Use Hermes memory"), prefs.grokLiveMemory == true, { on -> vm.updatePreferences { it.copy(grokLiveMemory = if (on) true else null) } })
        Footnote(t("Grok Live gets what your Hermes agent remembers about you. That memory is sent to xAI with the conversation. Applies to the next conversation."))
        SettingsToggle(t("Use Hermes personality"), prefs.grokLivePersonality == true, { on -> vm.updatePreferences { it.copy(grokLivePersonality = if (on) true else null) } })
        Footnote(t("Grok Live talks with the personality in this profile's SOUL.md. SOUL.md is sent to xAI with the conversation. Applies to the next conversation."))
        SaveCallsToggle(vm, prefs)
    }
}

/** How every live mode sounds (iOS LiveVoiceStyleSection, #290). */
@Composable
private fun LiveCallStyleSection(vm: VoiceSettingsViewModel, prefs: VoiceProfilePreferences) {
    val style = LiveVoiceStyle.of(prefs)
    var greeting by remember(prefs.liveVoiceGreeting) { mutableStateOf(prefs.liveVoiceGreeting.orEmpty()) }
    SettingsSection(t("Live call style")) {
        SettingsToggle(t("Greet me when a call connects"), prefs.liveVoiceGreeting != null, { on -> vm.updatePreferences { it.copy(liveVoiceGreeting = if (on) "" else null) } })
        if (prefs.liveVoiceGreeting != null) {
            OutlinedTextField(
                greeting,
                onValueChange = { text ->
                    greeting = text
                    vm.updatePreferences { it.copy(liveVoiceGreeting = LiveVoiceStyle.cleanedGreeting(text)) }
                },
                label = { Text(t("Greeting")) },
                placeholder = { Text(t("A short greeting of its own")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        Footnote(t("The voice model greets you as soon as a call connects, so you know it's live. Leave the greeting empty for one in its own words. GPT-Live needs an up-to-date Hermes notifier plugin for this. Applies to the next call."))
        val tones = listOf("" to t("Model default")) + LiveVoiceTone.entries.map { it.raw to it.label }
        ChoiceRow(t("Tone"), style.tone?.label ?: t("Model default"), tones) { raw ->
            vm.updatePreferences { it.copy(liveVoiceTone = raw.ifEmpty { null }) }
        }
        Footnote(t("How live calls sound. Model default keeps the voice model's tone and your Hermes server's persona. Applies to the next call."))
        ChoiceRow(t("Answer length"), style.answerLength.label, LiveVoiceAnswerLength.entries.map { it.raw to it.label }) { raw ->
            vm.updatePreferences { it.copy(liveVoiceAnswerLength = if (raw == LiveVoiceAnswerLength.STANDARD.raw) null else raw) }
        }
        Footnote(t("How much the voice model says when you ask something. Concise gives the key point in one to three sentences, Default adds the details that make it useful, and Detailed goes in depth. Applies to the next call."))
        SettingsToggle(t("Backchannels"), prefs.liveVoiceBackchannels != false, { on -> vm.updatePreferences { it.copy(liveVoiceBackchannels = if (on) null else false) } })
        Footnote(t("Short sounds like “mm-hmm” while you talk. Turn them off for a quieter listener. Applies to the next call."))
        SettingsToggle(t("Ask before sending to Hermes"), prefs.liveVoiceAskBeforeSending == true, { on -> vm.updatePreferences { it.copy(liveVoiceAskBeforeSending = if (on) true else null) } })
        Footnote(t("The voice model tells you what it will send and waits for your OK before Hermes starts on it. Say “send it to Hermes” to skip the question. Change it during a call with the call screen's button or by asking. Applies to the next call."))
    }
}

private const val TAG_SEPARATOR = "\u001F"
private fun modelTag(provider: String?, model: String) = (provider ?: "") + TAG_SEPARATOR + model
private fun parseModelTag(tag: String): Pair<String?, String> {
    val index = tag.indexOf(TAG_SEPARATOR)
    if (index < 0) return null to tag
    return tag.substring(0, index).ifEmpty { null } to tag.substring(index + 1)
}

private fun modelChoices(defaultTitle: String, selection: String, providers: List<ModelProvider>): List<Pair<String, String>> {
    val choices = mutableListOf("" to defaultTitle)
    if (selection.isNotEmpty() && providers.none { p -> p.models.any { modelTag(p.slug, it) == selection } }) choices += selection to parseModelTag(selection).second
    for (provider in providers) for (model in provider.models) choices += modelTag(provider.slug, model) to "$model · ${provider.name}"
    return choices
}

private fun reasoningLevels(offTitle: String): List<Pair<String, String>> = listOf(
    "none" to offTitle,
    "minimal" to L10n.tr("Minimal"),
    "low" to L10n.tr("Low"),
    "medium" to L10n.tr("Medium"),
    "high" to L10n.tr("High"),
    "xhigh" to L10n.tr("Extra High"),
    "max" to L10n.tr("Max"),
)

@Composable
private fun rememberModelProviders(vm: VoiceSettingsViewModel): List<ModelProvider> {
    var providers by remember { mutableStateOf<List<ModelProvider>>(emptyList()) }
    LaunchedEffect(Unit) {
        providers = runCatching {
            vm.hub.hermes.rpc("model.options", buildJsonObject { put("explicit_only", true) }).obj?.let(MessageNormalizer::modelOptions)?.providers
        }.getOrNull().orEmpty()
    }
    return providers
}

/** The model and reasoning for Hermes sessions that voice background jobs create (iOS VoiceJobModelSettingsSection). */
@Composable
internal fun VoiceJobModelSection(vm: VoiceSettingsViewModel, prefs: VoiceProfilePreferences) {
    val providers = rememberModelProviders(vm)
    val selection = prefs.voiceJobModel?.let { modelTag(prefs.voiceJobProvider, it) }.orEmpty()
    SettingsSection(t("Voice jobs")) {
        val models = modelChoices(t("Profile default"), selection, providers)
        ChoiceRow(t("Model"), models.firstOrNull { it.first == selection }?.second ?: selection, models) { tag ->
            val (provider, model) = if (tag.isEmpty()) null to null else parseModelTag(tag)
            vm.updatePreferences { it.copy(voiceJobModel = model, voiceJobProvider = provider) }
        }
        val levels = listOf("" to t("Profile default")) + reasoningLevels(L10n.tr("None"))
        val reasoning = prefs.voiceJobReasoningEffort.orEmpty()
        ChoiceRow(t("Reasoning"), levels.firstOrNull { it.first == reasoning }?.second ?: reasoning, levels) { level ->
            vm.updatePreferences { it.copy(voiceJobReasoningEffort = level.ifEmpty { null }) }
        }
        Footnote(t("The model Hermes uses for background jobs started by voice. A fast model with low reasoning keeps spoken requests quick; your chats keep the profile's model."))
    }
}

/** Hermes' model slot for spoken classic-voice turns (`auxiliary.voice_chat`); hidden on a Hermes without it. */
private data class VoiceReplyModel(val provider: String, val model: String, val reasoningEffort: String?)

@Composable
internal fun VoiceReplyModelSection(vm: VoiceSettingsViewModel) {
    val hermes = vm.hub.hermes
    val profile = vm.profile
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<VoiceReplyModel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val providers = rememberModelProviders(vm)
    val query = mapOf("profile" to profile.takeIf { it != "default" })
    LaunchedEffect(profile) {
        saved = runCatching {
            val response = hermes.rest { it.getJson("api/model/auxiliary", query) }
            val row = response["tasks"].arr?.mapNotNull { it.obj }?.firstOrNull { it["task"].str == VOICE_REPLY_TASK } ?: return@runCatching null
            VoiceReplyModel(row["provider"].str?.trim()?.ifEmpty { null } ?: "auto", row["model"].str?.trim().orEmpty(), row["reasoning_effort"].str?.trim()?.ifEmpty { null })
        }.getOrNull()
    }
    val current = saved ?: return
    fun save(next: VoiceReplyModel) {
        if (next == current) return
        scope.launch {
            try {
                val result = hermes.rest {
                    it.sendJson("POST", "api/model/set", buildJsonObject {
                        put("scope", "auxiliary")
                        put("task", VOICE_REPLY_TASK)
                        put("provider", next.provider.ifEmpty { "auto" })
                        put("model", next.model)
                        // An explicit null means "same as chats".
                        put("reasoning_effort", next.reasoningEffort?.let(::JsonPrimitive) ?: JsonNull)
                        put("profile", profile)
                    }, query)
                }
                if (result["confirm_required"].bool == true) {
                    error = result["confirm_message"].str ?: L10n.tr("Hermes requires confirmation before using this model.")
                } else {
                    error = null
                    saved = next
                }
            } catch (e: Exception) {
                error = L10n.tr("Could not save the voice reply model: %s", liveErrorMessage(e))
            }
        }
    }
    SettingsSection(t("Voice replies")) {
        val selection = if (current.model.isEmpty()) "" else modelTag(current.provider, current.model)
        val models = modelChoices(t("Same as chats"), selection, providers)
        ChoiceRow(t("Model"), models.firstOrNull { it.first == selection }?.second ?: selection, models) { tag ->
            val pick = if (tag.isEmpty()) null else parseModelTag(tag)
            // "Same as chats" keeps a provider pinned without a model as is.
            val unpinned = if (current.model.isEmpty()) current.provider else "auto"
            save(current.copy(provider = pick?.let { it.first ?: "auto" } ?: unpinned, model = pick?.second.orEmpty()))
        }
        val levels = reasoningLevels(L10n.tr("Off")) + (INHERIT_REASONING to t("Same as chats"))
        val reasoning = current.reasoningEffort ?: INHERIT_REASONING
        ChoiceRow(t("Reasoning"), levels.firstOrNull { it.first == reasoning }?.second ?: reasoning, levels) { level ->
            save(current.copy(reasoningEffort = if (level == INHERIT_REASONING) null else level))
        }
        Footnote(t("The model Hermes answers you with in a voice conversation. Reasoning is off by default so replies start sooner; typed messages keep the chat's model. Hermes Desktop uses this setting too."))
        error?.let {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val VOICE_REPLY_TASK = "voice_chat"
private const val INHERIT_REASONING = "inherit"
