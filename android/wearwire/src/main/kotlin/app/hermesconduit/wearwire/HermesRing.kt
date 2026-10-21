package app.hermesconduit.wearwire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/*
 * A call from Hermes that rings on the phone and the watch alike (port of
 * the iOS HermesRing.swift, designs/hermes-calls-watch.md). On iOS the relay
 * pushes the call to both devices with the same ring, and the one that
 * answers or declines settles it at the relay's `/v1/rings/{id}/settled`,
 * which tells the other to stop ringing: the iPhone's ringing CallKit call
 * cuts its link to the Watch. On Android calls come from the gateway socket
 * on the phone and the Data Layer link stays up while either rings, so the
 * phone rings the watch itself ([WearMessage.Ring]) and keeps the ring's
 * ledger in the relay's place ([HermesRingLedger]): the watch settles there
 * ([WearMessage.RingSettle], answered like the relay's settle), and the
 * phone's stop comes as [WearMessage.RingSettled], the relay's stop push.
 * Pure.
 */

/** How a ring was settled. */
enum class HermesRingOutcome(val raw: String) {
    ANSWERED("answered"),
    DECLINED("declined");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/** The device that settled a ring. */
enum class HermesRingDevice(val raw: String) {
    PHONE("phone"),
    WATCH("watch");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/** What settling a ring came to (iOS HermesRingSettleResult). */
sealed class HermesRingSettleResult {
    /** This device settled it; the other stops ringing. */
    data object Settled : HermesRingSettleResult()
    /** The other device got there first. */
    data class AlreadySettled(val outcome: HermesRingOutcome, val by: HermesRingDevice) : HermesRingSettleResult()
    /** The phone forgot the ring (it expired, or the app restarted) or couldn't be reached: the other device rings out. */
    data object Failed : HermesRingSettleResult()

    val logLabel: String
        get() = when (this) {
            Settled -> "settled"
            is AlreadySettled -> "alreadySettled(${outcome.raw}, by: ${by.raw})"
            Failed -> "failed"
        }

    /** The phone's answer to a [WearMessage.RingSettle], as the relay answers a settle: `ok`, or the 409's `settled`. */
    fun reply(): JsonObject = buildJsonObject {
        when (this@HermesRingSettleResult) {
            Settled -> put("ok", true)
            is AlreadySettled -> put("settled", buildJsonObject { put("outcome", outcome.raw); put("by", by.raw) })
            Failed -> put("ok", false)
        }
    }

    fun encoded(): ByteArray = reply().toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** The phone's reply read back; anything unreadable failed. */
        fun decode(bytes: ByteArray?): HermesRingSettleResult {
            val o = runCatching { WearWire.json.parseToJsonElement((bytes ?: return Failed).toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return Failed
            if (o.b("ok") == true) return Settled
            val settled = o["settled"] as? JsonObject ?: return Failed
            val outcome = HermesRingOutcome.of(settled.s("outcome")) ?: return Failed
            val by = HermesRingDevice.of(settled.s("by")) ?: return Failed
            return AlreadySettled(outcome, by)
        }
    }
}

object HermesRingIds {
    private val random = SecureRandom()

    /** As the relay's: 22 base64url characters. */
    fun isID(value: String): Boolean = value.length == 22 && value.all(::isBase64URL)

    /** A new ring's id: 16 random bytes, base64url. */
    fun newID(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun isBase64URL(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
}

/**
 * The phone's record of the rings it sent the watch, in the relay's place:
 * the first settle stands, and a ring is kept as long as the relay keeps
 * one, for a watch call that answers it later. Not thread-safe: used on the
 * main thread.
 */
class HermesRingLedger(private val lifeMs: Long = LIFE_MS) {
    private data class Ring(val atMs: Long, val outcome: HermesRingOutcome? = null, val by: HermesRingDevice? = null)

    private val rings = LinkedHashMap<String, Ring>()

    fun open(id: String, nowMs: Long) {
        prune(nowMs)
        if (id !in rings) rings[id] = Ring(nowMs)
    }

    fun knows(id: String, nowMs: Long): Boolean {
        prune(nowMs)
        return id in rings
    }

    /** How [id] was settled, if it was. */
    fun settled(id: String, nowMs: Long): Pair<HermesRingOutcome, HermesRingDevice>? {
        prune(nowMs)
        val ring = rings[id] ?: return null
        return ring.outcome?.let { it to ring.by!! }
    }

    /** As the relay's settle: once, by the first device; an unknown ring fails. */
    fun settle(id: String, by: HermesRingDevice, outcome: HermesRingOutcome, nowMs: Long): HermesRingSettleResult {
        prune(nowMs)
        val ring = rings[id] ?: return HermesRingSettleResult.Failed
        if (ring.outcome != null) return HermesRingSettleResult.AlreadySettled(ring.outcome, ring.by!!)
        rings[id] = ring.copy(outcome = outcome, by = by)
        return HermesRingSettleResult.Settled
    }

    private fun prune(nowMs: Long) {
        rings.entries.removeAll { nowMs - it.value.atMs >= lifeMs }
        while (rings.size > MAXIMUM_RINGS) rings.remove(rings.keys.first())
    }

    companion object {
        /** As long as the iPhone keeps a ring's target for a Watch call that answers it (HermesNativeCalls.ringTargetLife). */
        const val LIFE_MS = 600_000L
        const val MAXIMUM_RINGS = 32
    }
}

/** When a call from Hermes rings the watch too (iOS PushNotificationService.ringingWatchToken, HermesWatchCalls.receive). */
object HermesWatchRingPlan {
    /** How long a call rings before it's missed, as on the phone. */
    const val RINGS_FOR_MS = 45_000L
    /** A call this late has rung out on the phone already. */
    const val LATE_AFTER_MS = 90_000L
    /** How long a settled ring is remembered on the watch, for a ring that comes after its stop. */
    const val SETTLED_LIFE_MS = 600_000L

    /**
     * Whether the phone rings the watch with a call it rings itself: only
     * alongside the phone, only on a watch that said it can ring (its
     * [token]), and only while the voice it answers with can start: the
     * watch's turn-by-turn voice through the phone can't open with the call.
     */
    fun ringsWatch(phoneRings: Boolean, token: String?, engine: String?, voices: Map<String, Boolean>): Boolean {
        if (!phoneRings || token.isNullOrEmpty()) return false
        if (engine == WatchCallVoices.PHONE) return false
        return WatchCallVoices.rings(engine, voices)
    }

    /** On the watch: whether a ring rings there, or the phone rings it alone. */
    fun ringsHere(sentAtMs: Long?, nowMs: Long, busy: Boolean, settled: Boolean): Boolean =
        !isLate(sentAtMs, nowMs) && !busy && !settled

    fun isLate(sentAtMs: Long?, nowMs: Long): Boolean = sentAtMs != null && nowMs - sentAtMs > LATE_AFTER_MS
}
