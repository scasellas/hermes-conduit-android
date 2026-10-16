package app.hermesconduit.android.ui.device

import android.content.Context
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.CanonicalJson
import app.hermesconduit.android.model.HealthSyncCrypto
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.LocationCrypto
import app.hermesconduit.android.model.SyncDestination
import app.hermesconduit.android.model.VoiceHostTool
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.model.VoiceReadSeal
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.health.HealthSync
import app.hermesconduit.android.ui.health.HealthSyncReason
import app.hermesconduit.android.ui.health.VOICE_TOOLS_CAPABILITY
import app.hermesconduit.android.ui.location.LocationSharing
import app.hermesconduit.android.ui.location.LocationSyncEngine
import app.hermesconduit.android.ui.location.MapsVoiceTool
import app.hermesconduit.android.ui.phone.PhoneSync
import app.hermesconduit.android.ui.phone.PhoneSyncEngine
import app.hermesconduit.android.ui.phone.PhoneSyncReason
import app.hermesconduit.android.ui.phone.PhoneVoiceTools
import app.hermesconduit.android.ui.plugin.PluginStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The data-providing side of a live voice call's host tools (iOS
 * AppState+VoiceHostTools): the user's shared health data
 * (`conduit_health`) and location (`conduit_location`) read through the
 * plugin's sealed `/voice-tools` route, the calendar and phone status
 * (`conduit_calendar`, `conduit_phone`), the phone's own calendar and alarm
 * tools, and maps lookups on this phone. Each read checks the mode's consent
 * again. A live voice port calls [tools] when a call starts and [call] for
 * each function call.
 */
class VoiceHostTools(private val context: Context) {
    private val health get() = HealthSync.of(context)
    private val phone get() = PhoneSync.of(context)
    private val location get() = LocationSharing.of(context)

    private fun isActive(destination: SyncDestination): Boolean =
        DeviceSharing.activeDashboardID(context) == destination.dashboardID && DeviceSharing.activeProfile(context) == destination.profile

    /** The kinds of sealed host data a call on `mode` may read now. */
    fun readableKeys(mode: LiveVoiceMode): Set<String> {
        val keys = mutableSetOf<String>()
        val preferences = health.preferences.value
        if (preferences.letsVoiceRead(mode) && preferences.destination?.let(::isActive) == true) keys += "health"
        val binding = location.binding
        val status = PluginStatusRepository.of(context).status.value
        if (LocationSharing.servesLocationVoice(status) && location.letsVoiceRead(mode) && binding != null &&
            DeviceSharing.activeDashboardID(context) == binding.dashboardID && DeviceSharing.activeProfile(context) == binding.profile
        ) keys += "location"
        return keys
    }

    /**
     * What a call on `mode` may use: the host's tools (null when the host
     * couldn't be asked) and the phone's own. Starts a sync of each, so the
     * call reads fresh data.
     */
    suspend fun tools(mode: LiveVoiceMode): Pair<List<VoiceHostTool>?, List<VoiceHostTool>> {
        val keys = readableKeys(mode)
        val phonePreferences = phone.preferences.value
        val phoneAllowed = phonePreferences.letsVoiceUse(mode) && phonePreferences.destination?.let(::isActive) == true
        val engine = phone.engine
        val phoneTools = if (phoneAllowed) PhoneVoiceTools.tools(phonePreferences, engine.calendarSource.eventAccess, engine.calendarSource.remindersAuthorized, engine.alarmSource.authorized) else emptyList()
        val local = phoneTools + MapsVoiceTool.tool
        if (keys.isEmpty() && !phoneAllowed) return emptyList<VoiceHostTool>() to local
        val status = PluginStatusRepository.of(context).status.value
        val dashboard = DeviceSharing.activeDashboardID(context) ?: return null to local
        if (!status.reports(VOICE_TOOLS_CAPABILITY)) return null to local
        val scope = context.hermes.scope
        if ("health" in keys && !health.engine.isSyncing.value) scope.launch { health.run(HealthSyncReason.VOICE_CALL) }
        if ("location" in keys) scope.launch { location.prepareVoiceReads() }
        if (phoneAllowed && !engine.isSyncing.value) scope.launch { phone.run(PhoneSyncReason.VOICE_CALL) }
        val request = DeviceSharing.request(context, dashboard, DeviceSharing.activeProfile(context)) ?: return null to local
        return try {
            val response = request("GET", PATH, emptyMap(), null)
            VoiceHostTool.tools(response).filter { it.key in keys || (it.key == "phone" && phoneAllowed) } to local
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null to local
        }
    }

    /** One call for the running call: what the model hears back. */
    suspend fun call(tool: VoiceHostTool, arguments: JsonObject, mode: LiveVoiceMode?): Map<String, String> {
        val args = tool.arguments(arguments)
        return when (tool.key) {
            "health", "location" -> sealedRead(tool, args, mode)
            "phone", PhoneVoiceTools.KEY -> phoneTool(tool, args, mode)
            MapsVoiceTool.KEY -> {
                if (mode == null || tool.name != MapsVoiceTool.NAME) return NOT_AVAILABLE
                val binding = location.binding
                val sharedHere = binding != null && DeviceSharing.activeDashboardID(context) == binding.dashboardID && DeviceSharing.activeProfile(context) == binding.profile
                MapsVoiceTool.run(context, args, mode, location, sharedHere)
            }
            else -> NOT_AVAILABLE
        }
    }

    private suspend fun phoneTool(tool: VoiceHostTool, arguments: JsonObject, mode: LiveVoiceMode?): Map<String, String> {
        val engine = phone.engine
        val destination = engine.preferences.value.destination
        if (mode == null || !engine.preferences.value.letsVoiceUse(mode) || destination == null || !isActive(destination)) {
            return mapOf("error" to "The user hasn't let this voice call use their calendar, or it isn't reachable now.")
        }
        if (tool.key == PhoneVoiceTools.KEY) {
            val result = PhoneVoiceTools.run(tool, arguments, engine) { prompt, approve ->
                engine.answer(prompt, approve)
                context.hermes.scope.launch { phone.run(PhoneSyncReason.ANSWERED) }
            }
            if (result["error"] == null) {
                recordPhoneUse(tool, arguments, mode)
                if (tool.name == PhoneVoiceTools.ALARM_TOOL) context.hermes.scope.launch { phone.run(PhoneSyncReason.ANSWERED) }
            }
            return result
        }
        val request = DeviceSharing.request(context, destination.dashboardID, destination.profile)
            ?: return mapOf("error" to "The calendar isn't reachable just now.")
        return try {
            val result = engine.voiceRead(tool.name, arguments, mode.storageID, "$PATH/call", request)
            val answer = VoiceHostTool.modelResult(result)
            if (answer["error"] == null) recordPhoneUse(tool, arguments, mode)
            answer
        } catch (_: PhoneSyncEngine.VoiceNotReady) {
            mapOf("error" to "The calendar isn't ready for voice yet; it will be after the next sync.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The detail stays here: it can name the host, and the model's company would get it.
            mapOf("error" to "The calendar couldn't be read just now.")
        }
    }

    private fun recordPhoneUse(tool: VoiceHostTool, arguments: JsonObject, mode: LiveVoiceMode) {
        val action = when (tool.name) {
            PhoneVoiceTools.ADD_TOOL -> arguments["kind"].str?.let { "add $it" } ?: tool.name
            PhoneVoiceTools.ALARM_TOOL -> arguments["kind"].str?.let { "set $it" } ?: tool.name
            PhoneVoiceTools.CHANGES_TOOL -> "changes ${arguments["action"].str.orEmpty()}"
            else -> arguments["action"].str ?: tool.name
        }
        DeviceSharing.recordVoiceRead(context, VoiceReadLog.PHONE_KEY, mode, action)
    }

    private suspend fun sealedRead(tool: VoiceHostTool, arguments: JsonObject, mode: LiveVoiceMode?): Map<String, String> {
        if (mode == null || tool.key !in readableKeys(mode)) {
            return mapOf("error" to if (tool.key == "location") "The user hasn't let this voice call read their location, or it isn't reachable now."
                else "The user hasn't let this voice call read their health data, or it isn't reachable now.")
        }
        val dashboard = DeviceSharing.activeDashboardID(context) ?: return NOT_AVAILABLE
        val request = DeviceSharing.request(context, dashboard, DeviceSharing.activeProfile(context)) ?: return NOT_AVAILABLE
        val callID = VoiceReadSeal.newCallID()
        val plaintext = CanonicalJson.bytes(buildJsonObject {
            put("call_id", callID); put("args", arguments); put("provider", mode.storageID)
        })
        return if (tool.key == "health") {
            val (deviceID, key) = health.engine.voiceReadKey() ?: return mapOf("error" to "Health data isn't ready for voice yet; it will be after the next sync.")
            try {
                val envelope = HealthSyncCrypto.voiceRequest(tool.name, plaintext, key, deviceID, callID)
                val response = request("POST", "$PATH/call", emptyMap(), envelope)
                val result = HealthSyncCrypto.openVoiceAnswer(response, tool.name, key, deviceID, callID)
                DeviceSharing.recordVoiceRead(context, VoiceReadLog.HEALTH_KEY, mode, arguments["action"].str.orEmpty())
                VoiceHostTool.modelResult(result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mapOf("error" to "The health data couldn't be read just now.")
            }
        } else {
            val key = location.voiceReadKey() ?: run {
                context.hermes.scope.launch { location.prepareVoiceReads() }
                return mapOf("error" to "The location isn't ready for voice yet; ask again in a moment.")
            }
            try {
                val envelope = LocationCrypto.voiceRequest(tool.name, plaintext, key, callID)
                val response = request("POST", "$PATH/call", emptyMap(), envelope)
                VoiceHostTool.modelResult(LocationCrypto.openVoiceAnswer(response, tool.name, key, callID))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (SyncSupport.httpCode(e) == 409 && LocationSyncEngine.needsNewKey(SyncSupport.httpDetail(e))) {
                    // The host forgot this phone's key (a wipe): the next send agrees a new one.
                    context.hermes.scope.launch { location.flush(settingsOnly = true) }
                }
                mapOf("error" to "The location couldn't be read just now.")
            }
        }
    }

    /** The briefing line for a call whose mode may not use the calendar and phone shared here. */
    fun phoneSettingHint(mode: LiveVoiceMode): String {
        val preferences = phone.preferences.value
        if (preferences.destination?.let(::isActive) != true) return ""
        return PhoneVoiceTools.settingHint(preferences, mode)
    }

    companion object {
        const val PATH = "${SyncSupport.PLUGIN_BASE}/voice-tools"
        private val NOT_AVAILABLE = mapOf("error" to "That isn't available on this call.")

        @Volatile private var instance: VoiceHostTools? = null
        fun of(context: Context): VoiceHostTools = instance ?: synchronized(this) {
            instance ?: VoiceHostTools(context.applicationContext).also { instance = it }
        }
    }
}
