package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The notifier plugin's Android push routes (docs/ANDROID_PUSH.md):
 * `POST …/push/android/register` and `…/unregister`, scoped to a profile like
 * every plugin route. The body carries what iOS hands the relay and the
 * plugin for the same job: the installation id, the preferences (iOS
 * `ConduitNotificationPreferences`, same keys) and the end-to-end key
 * (iOS `POST /e2e`: `kid` and the base64url secret).
 */
object AndroidPushWire {
    const val PLATFORM = "android-unifiedpush"
    const val REGISTER = "push/android/register"
    const val UNREGISTER = "push/android/unregister"

    /** The iOS preference keys, from the Android "Notify me when" toggles. */
    fun preferences(enabled: Boolean, value: (String) -> Boolean, keys: PreferenceKeys): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("approval_needed", value(keys.approval))
        put("input_needed", value(keys.input))
        put("response_ready", value(keys.response))
        put("turn_failed", value(keys.failed))
        put("background_task_finished", value(keys.backgroundTask))
        put("completion_sound", value(keys.completionSound))
        put("attention_sound", value(keys.attentionSound))
        put("show_previews", value(keys.previews))
        put("decision_cards", value(keys.decisionCards))
    }

    /** The Android settings keys behind each iOS preference. */
    data class PreferenceKeys(
        val approval: String,
        val input: String,
        val response: String,
        val failed: String,
        val backgroundTask: String,
        val completionSound: String,
        val attentionSound: String,
        val previews: String,
        val decisionCards: String,
    )

    data class Endpoint(val url: String, val p256dh: String? = null, val auth: String? = null, val temporary: Boolean = false)

    fun registerBody(
        installationID: String,
        endpoint: Endpoint,
        preferences: JsonObject,
        kid: String,
        secret: ByteArray,
        appVersion: String,
        device: String,
    ): JsonObject = buildJsonObject {
        put("installation_id", installationID)
        put("endpoint", endpoint.url)
        put("platform", PLATFORM)
        put("preferences", preferences)
        put("e2e", buildJsonObject {
            put("v", 1)
            put("kid", kid)
            put("secret", SealCrypto.base64Url(secret))
        })
        // RFC 8291 keys the connector made for this registration: a plugin that
        // has them encrypts the push body, so the push server sees nothing.
        if (endpoint.p256dh != null && endpoint.auth != null) {
            put("webpush", buildJsonObject {
                put("p256dh", endpoint.p256dh)
                put("auth", endpoint.auth)
            })
        }
        put("app_version", appVersion)
        put("device", device)
    }

    fun unregisterBody(installationID: String): JsonObject = buildJsonObject {
        put("installation_id", installationID)
        put("platform", PLATFORM)
    }

    sealed class RegisterOutcome {
        /** The plugin holds the key: pushes for this pairing are sealed with it. */
        data class Registered(val gatewayID: String) : RegisterOutcome()
        /** The answer didn't confirm this registration and key. */
        data class Refused(val detail: String) : RegisterOutcome()
    }

    /**
     * The plugin's answer: `{ok: true, installation_id, gateway_id, kid}`.
     * The key counts only when the plugin echoes it (iOS provisioning), and
     * `gateway_id` names the pairing its associated data binds.
     */
    fun registerOutcome(response: JsonObject, installationID: String, kid: String): RegisterOutcome {
        if (response["ok"].strictBool != true) return RegisterOutcome.Refused(response["error"].str ?: response["detail"].str ?: "not ok")
        if (response["installation_id"].strictString?.let { it != installationID } == true) return RegisterOutcome.Refused("another installation")
        if (response["kid"].strictString != kid) return RegisterOutcome.Refused("key not confirmed")
        val gateway = response["gateway_id"].strictString?.trim()?.takeIf { it.isNotEmpty() } ?: return RegisterOutcome.Refused("no gateway id")
        return RegisterOutcome.Registered(gateway)
    }
}
