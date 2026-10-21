package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one UnifiedPush message asks of this phone. */
sealed class PushAction {
    /** A notification, as the background connection would have posted it. */
    data class Notify(
        val type: String,
        val title: String?,
        val body: String?,
        val sessionId: String,
        val durableSessionId: String?,
        val profile: String?,
        val dashboardId: String?,
        /** An answerable approval (`approval.respond` by session key), when the push carries one. */
        val approval: ApprovalRequest?,
        val replayKey: String,
    ) : PushAction()

    /** A call from Hermes: rings like one from the socket. */
    data class Ring(val target: HermesCallTarget, val replayKey: String) : PushAction()

    /** The agent's on-demand locate or a maps request, answered through the location routes. */
    data class Locate(val request: LocationLocateRequest, val replayKey: String) : PushAction()

    /**
     * Anything else: a plain wake-up, a type this build doesn't know, or a
     * push that didn't verify. Nothing in it is used: Conduit reconnects and
     * looks for itself (the background sweep, once).
     */
    data class Wake(val type: String?, val reason: String) : PushAction()
}

/**
 * Decides what a push does from its [PushE2E.Evaluation] (iOS
 * PushNotificationService.target(for:), LocationLocateRequest.parse and the
 * VoIP call path, on one channel). Only verified content routes anywhere.
 */
object PushDispatch {
    const val WAKE_TYPE = "wake"
    val NOTIFY_TYPES = setOf("approval.needed", "input.needed", "response.ready", "turn.failed", "background_task.finished")

    fun plan(evaluation: PushE2E.Evaluation): PushAction {
        val verified = when (evaluation) {
            is PushE2E.Evaluation.Untrusted -> return PushAction.Wake(evaluation.type, if (evaluation.type == WAKE_TYPE) "wake" else "unverified")
            is PushE2E.Evaluation.Verified -> evaluation
        }
        val payload = verified.routingPayload
        val replayKey = verified.envelope.replayKey
        return when (val type = verified.type) {
            in NOTIFY_TYPES -> {
                val session = payload["session_id"].strictString?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return PushAction.Wake(type, "no session")
                PushAction.Notify(
                    type = type,
                    title = verified.content["title"].strictString?.takeIf { it.isNotBlank() },
                    body = verified.content["body"].strictString?.takeIf { it.isNotBlank() },
                    sessionId = session,
                    durableSessionId = listOf("stored_session_id", "session_key").firstNotNullOfOrNull { payload[it].strictString?.trim()?.takeIf { s -> s.isNotEmpty() } },
                    profile = payload["profile"].strictString?.trim()?.takeIf { it.isNotEmpty() },
                    dashboardId = payload["dashboard_id"].strictString,
                    approval = if (type == "approval.needed") approval(payload["decision"] as? JsonObject) else null,
                    replayKey = replayKey,
                )
            }
            HermesCallRequest.TYPE -> {
                val target = HermesCallTarget.parse(payload) ?: return PushAction.Wake(type, "no call")
                // The authenticated issue time when the push didn't say when the call was placed.
                PushAction.Ring(if (target.sentAtMs == null) target.copy(sentAtMs = verified.envelope.iat * 1000) else target, replayKey)
            }
            LocationLocateRequest.TYPE -> {
                val request = LocationLocateRequest.parseStub(payload) ?: return PushAction.Wake(type, "no request")
                PushAction.Locate(request, replayKey)
            }
            else -> PushAction.Wake(type, "unhandled")
        }
    }

    /**
     * An approval card: a session key to answer with, a description to show
     * and at least one usable choice (iOS `pendingDecision`, approval kind).
     */
    fun approval(decision: JsonObject?): ApprovalRequest? {
        decision ?: return null
        if (decision["kind"].strictString?.trim()?.lowercase() != "approval") return null
        val sessionKey = decision["session_key"].strictString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val description = decision["description"].strictString?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val choices = (decision["choices"] as? JsonArray ?: return null)
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() }
        if (choices.isEmpty()) return null
        return ApprovalRequest(requestId = "", sessionId = sessionKey, command = null, description = description, choices = choices)
    }
}
