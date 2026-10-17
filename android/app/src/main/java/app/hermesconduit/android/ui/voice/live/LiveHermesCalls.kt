package app.hermesconduit.android.ui.voice.live

import android.content.Context
import app.hermesconduit.android.calls.HermesCallNotifications
import app.hermesconduit.android.calls.HermesCalls
import app.hermesconduit.android.core.live.HermesCallHoldAnswer
import app.hermesconduit.android.core.live.HermesCallRefusal
import app.hermesconduit.android.core.live.HermesCallWatchAnswer
import app.hermesconduit.android.core.live.VoiceCallbackAvailability
import app.hermesconduit.android.core.live.VoiceCallbackBackend
import app.hermesconduit.android.core.live.VoiceCallbackTarget
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.HermesCallRequest
import app.hermesconduit.android.model.HermesCallTarget
import app.hermesconduit.android.model.HermesCallerName
import app.hermesconduit.android.model.HermesCallsWire
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.model.HermesCallHoldAnswer as WireHold
import app.hermesconduit.android.model.HermesCallRefusal as WireRefusal
import app.hermesconduit.android.model.HermesCallWatchAnswer as WireWatch
import app.hermesconduit.android.model.VoiceCallbackAvailability as WireAvailability

/**
 * "Call me when it's done" for the live voice modes (#449): the supervisor's
 * callback seam bound to [HermesCalls]' watches. The host's refusals become
 * [HermesCallRefusal]s the live model hears about.
 */
object LiveHermesCalls {
    fun availability(value: WireAvailability): VoiceCallbackAvailability = when (value) {
        WireAvailability.AVAILABLE -> VoiceCallbackAvailability.AVAILABLE
        WireAvailability.CALLS_OFF -> VoiceCallbackAvailability.CALLS_OFF
        WireAvailability.WHEN_ASKED_OFF -> VoiceCallbackAvailability.WHEN_ASKED_OFF
        WireAvailability.NOT_PAIRED -> VoiceCallbackAvailability.NOT_PAIRED
        WireAvailability.UNSUPPORTED -> VoiceCallbackAvailability.UNSUPPORTED
    }

    /** The host's refusal of a watch, as the supervisor reads it; the error itself for anything a retry may fix. */
    fun refusal(error: Throwable): Throwable {
        val http = error as? DashboardHttpException ?: return error
        return when (val refusal = HermesCallsWire.refusal(http.code, http.detail)) {
            is WireRefusal.Unavailable -> HermesCallRefusal(availability(refusal.availability))
            WireRefusal.TooMany -> HermesCallRefusal(null, tooMany = true)
            null -> error
        }
    }

    fun watchAnswer(answer: WireWatch): HermesCallWatchAnswer = when (answer) {
        is WireWatch.Watching -> HermesCallWatchAnswer.Watching(answer.id)
        is WireWatch.Ended -> HermesCallWatchAnswer.Ended(answer.kind?.raw)
    }

    fun holdAnswer(answer: WireHold): HermesCallHoldAnswer = when (answer) {
        WireHold.Watching -> HermesCallHoldAnswer.Watching
        is WireHold.Ended -> HermesCallHoldAnswer.Ended(answer.kind?.raw)
        WireHold.Gone -> HermesCallHoldAnswer.Gone
    }

    fun backend(context: Context, supported: () -> Boolean): VoiceCallbackBackend {
        val calls = HermesCalls.get(context)
        val hermes = context.hermes
        fun profile(name: String?) = name ?: hermes.profile.value
        return VoiceCallbackBackend(
            availability = {
                val status = calls.status.value
                if (!supported() || status == null) VoiceCallbackAvailability.UNSUPPORTED else availability(status.callbackAvailability)
            },
            watch = { target, hold, endedWithin ->
                try {
                    watchAnswer(calls.watch(target.sessionIds, target.title, profile(target.profile), hold, endedWithin))
                } catch (e: Exception) {
                    throw refusal(e)
                }
            },
            hold = { id, name, seconds ->
                try {
                    holdAnswer(calls.hold(id, profile(name), seconds))
                } catch (e: Exception) {
                    throw refusal(e)
                }
            },
            cancel = { id, name -> calls.cancelWatch(id, profile(name)) },
            notify = { target, kind -> postTalkNotice(context, target, kind) },
        )
    }

    /** "Hermes wants to talk": the job ended while the call it was promised to was on. */
    private fun postTalkNotice(context: Context, target: VoiceCallbackTarget, kind: String?) {
        val hermes = context.hermes
        val call = HermesCallRequest("", HermesCallRequest.Kind.of(kind), target.title, target.sessionIds)
        val routing = HermesCallTarget(target.profile, target.runtimeSessionId, target.storedSessionId, hermes.connection.value?.id, call, System.currentTimeMillis())
        HermesCallNotifications.postNotice(context, routing, missed = false, callerName = HermesCallerName.name(target.profile, null))
    }
}
