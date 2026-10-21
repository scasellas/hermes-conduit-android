package app.hermesconduit.android.calls

import android.content.Context
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.HermesCallTarget
import app.hermesconduit.android.watch.WatchDirectConnection
import app.hermesconduit.android.watch.WatchPhoneCallLog
import app.hermesconduit.android.watch.WatchVoiceReadiness
import app.hermesconduit.wearwire.HermesRingDevice
import app.hermesconduit.wearwire.HermesRingIds
import app.hermesconduit.wearwire.HermesRingLedger
import app.hermesconduit.wearwire.HermesRingOutcome
import app.hermesconduit.wearwire.HermesRingSettleResult
import app.hermesconduit.wearwire.HermesWatchRingPlan
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Calls from Hermes ringing the Wear OS watch too (iOS HermesNativeCalls'
 * ring and HermesRingSettler, designs/hermes-calls-watch.md). The phone
 * rings the watch over the Data Layer with each call it rings, and keeps the
 * ring's ledger in the relay's place: whichever device answers or declines
 * first settles it here, and the other stops ringing. The watch can ring
 * once it has sent its node id and voice ([tokenChanged], the iOS
 * `callsToken`), and only while that voice can start. Main thread only.
 */
class HermesWatchRing(context: Context) {
    private val app = context.applicationContext
    private val settings = app.appSettings
    private val log by lazy { WatchPhoneCallLog.get(app) }
    private val messages by lazy { Wearable.getMessageClient(app) }
    private val ledger = HermesRingLedger()
    /** Each ring's call, for a watch call that answers it, as long as the ledger keeps the ring. */
    private val targets = LinkedHashMap<String, HermesCallTarget>()

    /** The watch's node id, which calls ring on; null when it can't ring. */
    val token: String? get() = settings.getString(TOKEN_KEY, "").ifEmpty { null }
    /** The voice an answered call starts with on the watch. */
    val engine: String? get() = settings.getString(ENGINE_KEY, "").ifEmpty { null }

    /** The watch's `callsToken`: it can ring (or can't any more), and with which voice. */
    fun tokenChanged(token: String?, engine: String?) {
        settings.set(TOKEN_KEY, token.orEmpty())
        settings.set(ENGINE_KEY, engine.orEmpty())
        log.note("watchCallsToken", mapOf("token" to (token != null), "engine" to (engine ?: "none")))
    }

    /** Whether a call ringing here rings the watch too, on [connection]'s voice checks. */
    fun ringsWatch(connection: WatchDirectConnection): Boolean =
        HermesWatchRingPlan.ringsWatch(phoneRings = true, token = token, engine = engine, voices = WatchVoiceReadiness.current(settings, connection))

    /** Rings the watch with a call ringing here: the ring's id, or null when the watch doesn't ring. */
    fun ring(target: HermesCallTarget, callerName: String, connection: WatchDirectConnection): String? {
        val node = token ?: return null
        if (!ringsWatch(connection)) return null
        val id = HermesRingIds.newID()
        val now = System.currentTimeMillis()
        ledger.open(id, now)
        targets[id] = target
        while (targets.size > HermesRingLedger.MAXIMUM_RINGS) targets.remove(targets.keys.first())
        send(node, WearMessage.Ring(id, callerName, target.sentAtMs))
        return id
    }

    /** This phone answered or declined: the watch stops ringing, unless it got there first. */
    fun settle(id: String, outcome: HermesRingOutcome): HermesRingSettleResult {
        val result = ledger.settle(id, HermesRingDevice.PHONE, outcome, System.currentTimeMillis())
        if (result == HermesRingSettleResult.Settled) token?.let { send(it, WearMessage.RingSettled(id, outcome, HermesRingDevice.PHONE)) }
        return result
    }

    /** The watch answered or declined (its [WearMessage.RingSettle]): the first word stands. */
    fun watchSettle(request: WearMessage.RingSettle): HermesRingSettleResult {
        val result = ledger.settle(request.id, request.by, request.outcome, System.currentTimeMillis())
        log.note("watchRingSettle", mapOf("outcome" to request.outcome.raw, "result" to result.logLabel))
        return result
    }

    /** The call a watch call answering [id] opens with, while the ring is kept. */
    fun target(id: String): HermesCallTarget? {
        if (!ledger.knows(id, System.currentTimeMillis())) {
            targets.remove(id)
            return null
        }
        return targets[id]
    }

    /** Whether the watch answered the ring of the call in [sessionId]: that call isn't this phone's to open. */
    fun answeredOnWatch(sessionId: String): Boolean {
        val now = System.currentTimeMillis()
        return targets.any { (id, target) ->
            target.sessionId == sessionId && ledger.settled(id, now) == (HermesRingOutcome.ANSWERED to HermesRingDevice.WATCH)
        }
    }

    private fun send(node: String, message: WearMessage) {
        val (path, bytes) = WearWire.encode(message)
        app.hermes.scope.launch {
            runCatching { messages.sendMessage(node, path, bytes).await() }
                .onFailure { log.note("watchRingNotSent", mapOf("path" to path, "error" to it.javaClass.simpleName)) }
        }
    }

    companion object {
        const val TOKEN_KEY = "hermesCalls.watchToken"
        const val ENGINE_KEY = "hermesCalls.watchEngine"
    }
}
