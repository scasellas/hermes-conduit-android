package app.hermesconduit.android.watch

import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Port of the iOS `WatchToolGrantClient`: asks the Hermes host's
 * conduit_push plugin for a Watch call's tool grant and ends it again, and
 * reads GPT-Live's Watch audio runtime. The grant lets a watch run
 * web_search and recall_memory (and, with the job setting, Hermes jobs)
 * through the push relay while it can't reach this phone: one call, one
 * profile, those tools, half an hour. It goes to the watch only; nothing here
 * logs or keeps its keys.
 *
 * The Wear OS bridge call runs every turn through this phone, so it needs no
 * grant; this client is the host side the direct (wrist-down) path uses.
 */
class WatchToolGrantClient(private val hermes: HermesConnection) {
    data class Grant(
        val grantId: String,
        val relayUrl: String,
        val key: String,
        val watchKey: String,
        val expiresAt: String?,
        val tools: List<String>,
        val maxCalls: Int,
        val maxJobs: Int?,
        val jobsCarriedFrom: String?,
        val audio: AudioBridge?,
        val hostJobProfiles: List<String>?,
    )

    data class AudioBridge(val url: String, val version: Int, val engines: List<String>)

    /** GPT-Live's WebRTC runtime on the host: ready, preparing, failed or missing. */
    data class AudioRuntime(val runtime: String, val reason: String? = null)

    private fun query(profile: String) = mapOf("profile" to profile.takeIf { it != "default" && it.isNotEmpty() })

    /** Throws when the host can't grant one (older plugin or relay, no pairing, no network). */
    suspend fun grant(
        tools: List<String>,
        profile: String,
        maxJobs: Int? = null,
        jobOptions: Map<String, String> = emptyMap(),
        carryJobsFrom: String? = null,
        jobProfiles: List<String> = emptyList(),
        audio: Boolean = false,
    ): Grant {
        val body = grantBody(tools, maxJobs, jobOptions, carryJobsFrom, jobProfiles, audio)
        val response = hermes.rest { it.sendJson("POST", GRANT_PATH, body, query(profile), TIMEOUT_MS) }
        return grant(response) ?: error(L10n.tr("The Watch tool grant couldn't be read."))
    }

    /** Best effort: an unrevoked grant ends on its own within the half hour. */
    suspend fun revoke(grantId: String, profile: String) {
        runCatching { hermes.rest { it.sendJson("POST", REVOKE_PATH, buildJsonObject { put("grant_id", grantId) }, query(profile), TIMEOUT_MS) } }
    }

    suspend fun audioStatus(profile: String): AudioRuntime =
        audioRuntime(hermes.rest { it.getJson(AUDIO_STATUS_PATH, query(profile), TIMEOUT_MS) })

    suspend fun prepareAudio(profile: String): AudioRuntime =
        audioRuntime(hermes.rest { it.sendJson("POST", AUDIO_PREPARE_PATH, buildJsonObject {}, query(profile), TIMEOUT_MS) })

    companion object {
        const val GRANT_PATH = "api/plugins/conduit_push/watch-tools/grant"
        const val REVOKE_PATH = "api/plugins/conduit_push/watch-tools/revoke"
        const val AUDIO_STATUS_PATH = "api/plugins/conduit_push/watch-audio/status"
        const val AUDIO_PREPARE_PATH = "api/plugins/conduit_push/watch-audio/prepare"
        /** Short: the grant is asked for while a call is set up. */
        const val TIMEOUT_MS = 4_000L
        const val GPT_LIVE = "gpt_live"
        /** Tools that carry jobs (WatchJobAnswer.tools). */
        val JOB_TOOLS = setOf("start_job", "list_jobs", "cancel_job")

        fun grantBody(
            tools: List<String>,
            maxJobs: Int?,
            jobOptions: Map<String, String>,
            carryJobsFrom: String?,
            jobProfiles: List<String>,
            audio: Boolean,
        ): JsonObject = buildJsonObject {
            put("tools", buildJsonArray { tools.forEach { add(JsonPrimitive(it)) } })
            if (audio) put("audio", true)
            if (tools.any { it in JOB_TOOLS }) {
                maxJobs?.let { put("max_jobs", it) }
                if (jobOptions.isNotEmpty()) put("job_options", buildJsonObject { jobOptions.forEach { (k, v) -> put(k, v) } })
                carryJobsFrom?.let { put("carry_jobs_from", it) }
                if (jobProfiles.isNotEmpty()) put("job_profiles", buildJsonArray { jobProfiles.forEach { add(JsonPrimitive(it)) } })
            }
        }

        fun audioRuntime(response: JsonObject): AudioRuntime {
            val gptLive = response["engines"].obj?.get(GPT_LIVE).obj
            return AudioRuntime(gptLive?.get("runtime").str ?: "missing", gptLive?.get("reason").str)
        }

        private fun int(o: JsonObject, key: String): Int? = (o[key] as? JsonPrimitive)?.intOrNull

        fun grant(response: JsonObject): Grant? {
            if (response["ok"].bool != true) return null
            val grantId = response["grant_id"].str?.takeIf { it.isNotEmpty() } ?: return null
            val relayUrl = response["relay_url"].str?.takeIf { it.startsWith("https://") } ?: return null
            val key = response["key"].str?.takeIf { it.isNotEmpty() } ?: return null
            val watchKey = response["watch_key"].str?.takeIf { it.isNotEmpty() } ?: return null
            val tools = response["tools"].arr?.mapNotNull { it.str }?.takeIf { it.isNotEmpty() } ?: return null
            val maxCalls = int(response, "max_calls")?.takeIf { it > 0 } ?: return null
            val audio = response["audio"].obj?.let { a ->
                val url = a["url"].str?.takeIf { it.startsWith("wss://") } ?: return@let null
                val version = int(a, "version") ?: return@let null
                AudioBridge(url, version, a["engines"].arr?.mapNotNull { it.str }.orEmpty())
            }
            return Grant(
                grantId, relayUrl, key, watchKey,
                expiresAt = response["expires_at"]?.let { (it as? JsonPrimitive)?.content },
                tools = tools, maxCalls = maxCalls,
                maxJobs = int(response, "max_jobs"),
                jobsCarriedFrom = response["jobs_carried_from"].str,
                audio = audio,
                hostJobProfiles = response["job_profiles"].arr?.mapNotNull { it.str },
            )
        }
    }
}
