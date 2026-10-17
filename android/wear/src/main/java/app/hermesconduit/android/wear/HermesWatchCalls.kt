package app.hermesconduit.android.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import app.hermesconduit.wearwire.HermesRingDevice
import app.hermesconduit.wearwire.HermesRingOutcome
import app.hermesconduit.wearwire.HermesRingSettleResult
import app.hermesconduit.wearwire.HermesWatchRingPlan
import app.hermesconduit.wearwire.WatchCallVoices
import app.hermesconduit.wearwire.WatchVoiceWire
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Calls from Hermes ringing on the watch (port of the iOS HermesWatchCalls,
 * designs/hermes-calls-watch.md). The phone rings the watch with each call
 * it rings ([WearMessage.Ring]), and the watch shows its own incoming call:
 * a full-screen CallStyle notification with Answer and Decline, and the
 * ringtone's haptics. Answering here starts a watch voice call that opens
 * with what Hermes called about: its start carries the ring, and the phone,
 * which got the call, builds the opening. Answering or declining on either
 * device settles the ring on the phone, which stops the other one ringing;
 * declined here, the phone reports it to Hermes as a decline there would be.
 * The watch rings only after telling the phone it can (the iOS
 * `callsToken`), and only while its voice can open a call.
 */
class HermesWatchCalls(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: PhoneLink,
    private val log: WatchCallLog,
    private val prefs: WatchPrefs,
    private val voice: () -> WatchVoiceCall,
) {
    /** The call ringing here, for the ringing screen. */
    data class Ringing(val id: String, val callerName: String)

    private val _ringing = MutableStateFlow<Ringing?>(null)
    val ringing: StateFlow<Ringing?> = _ringing

    private class Call(val ring: WearMessage.Ring, var answered: Boolean = false, var unanswered: Job? = null)
    /** The call ringing or being answered here; one at a time. */
    private var call: Call? = null
    /** Rings settled here or on the phone, for a ring that comes again, or after the stop. */
    private val settledRings = mutableMapOf<String, Long>()

    /** What the phone was last sent this launch. */
    private var sentToken: String? = null
    private var sentEngine: String? = null
    private var hasSentToken = false
    private var sending: Job? = null

    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val vibrator get() = context.getSystemService(Vibrator::class.java)

    /** A call from Hermes is ringing or answered here: opening the app starts no other call. */
    val holdsCall: Boolean get() = call != null

    fun start() {
        ensureChannel()
        // Calls ring here only while this voice can start: the phone hears of every change.
        scope.launch { prefs.engine.collect { sendToken() } }
        scope.launch { link.reachable.collect { if (it) sendToken() } }
    }

    // region The token

    /**
     * Whether a call can ring here: notifications on, the calls channel not
     * turned off, and the microphone allowed (an answered call talks at once).
     */
    val canRing: Boolean
        get() {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

    /**
     * Queued to the phone: this watch's node id while calls can ring here
     * (null once they can't), and the voice an answered call starts with.
     * Once a launch at least, so a phone that lost them gets them again.
     */
    fun sendToken() {
        if (sending?.isActive == true) return
        sending = scope.launch {
            val token = if (canRing) link.localNodeId() else null
            val engine = prefs.engine.value.callVoice ?: WatchCallVoices.PHONE
            if (hasSentToken && token == sentToken && engine == sentEngine) return@launch
            if (!link.queue(WatchVoiceWire.Message.CallsToken(token, null, engine))) return@launch
            sentToken = token
            sentEngine = engine
            hasSentToken = true
            log.note("hermesCallsToken", mapOf("token" to (token != null), "engine" to engine))
        }
    }

    // endregion

    // region Ringing

    /** The phone rings a call here too. */
    fun receive(ring: WearMessage.Ring) {
        val now = System.currentTimeMillis()
        val late = HermesWatchRingPlan.isLate(ring.sentAtMs, now)
        // One call at a time; a watch call already on keeps it.
        val busy = call != null || voice().isActive
        // The phone's stop came first.
        val settled = isSettled(ring.id, now)
        val rings = HermesWatchRingPlan.ringsHere(ring.sentAtMs, now, busy, settled) && canRing
        log.note("hermesCallPush", mapOf("late" to late, "busy" to busy, "settled" to settled, "rings" to rings))
        // Not rung here: the phone rings it alone.
        if (!rings) return
        val ringing = Call(ring)
        call = ringing
        _ringing.value = Ringing(ring.id, ring.callerName)
        postRinging(ring)
        startHaptics()
        ringing.unanswered = scope.launch {
            delay(HermesWatchRingPlan.RINGS_FOR_MS)
            if (call !== ringing || ringing.answered) return@launch
            log.note("hermesCallUnanswered")
            finish(ringing)
        }
    }

    /** The phone answered or declined a call that rang on both: this one stops. */
    fun settledOnPhone(settled: WearMessage.RingSettled) {
        noteSettled(settled.id)
        val ringing = call?.takeIf { it.ring.id == settled.id }
        log.note("hermesCallSettledOnPhone", mapOf("outcome" to settled.outcome.raw, "here" to (ringing != null)))
        // Answered here too: `connect` settles that race.
        if (ringing != null && !ringing.answered) finish(ringing)
    }

    private fun isSettled(id: String, now: Long): Boolean {
        settledRings.entries.removeAll { now - it.value >= HermesWatchRingPlan.SETTLED_LIFE_MS }
        return id in settledRings
    }

    private fun noteSettled(id: String) {
        val now = System.currentTimeMillis()
        isSettled(id, now)
        settledRings[id] = now
    }

    // endregion

    // region Answering

    /** Answer, from the notification or the ringing screen. */
    fun answer(id: String) {
        val ringing = call?.takeIf { it.ring.id == id && !it.answered }
        if (ringing == null) {
            log.note("hermesCallAnsweredAfterEnd")
            return
        }
        ringing.answered = true
        ringing.unanswered?.cancel()
        stopRingingOut()
        scope.launch { connect(ringing) }
    }

    /**
     * Tells the phone (it stops ringing), then starts the watch voice call,
     * whose start names the ring so the phone opens it with the call's news.
     */
    private suspend fun connect(ringing: Call) {
        val voice = voice()
        // A watch call the user started meanwhile keeps going, and the phone
        // goes on ringing: nothing is settled. So does a voice that can't open
        // with the call (the turn-by-turn voice through the phone).
        if (voice.isActive || voice.engine.callVoice == null) {
            log.note("hermesCallNotAnswered", mapOf("busy" to voice.isActive, "engine" to voice.engine.raw))
            finish(ringing)
            return
        }
        noteSettled(ringing.ring.id)
        val settled = link.settleRing(WearMessage.RingSettle(ringing.ring.id, HermesRingDevice.WATCH, HermesRingOutcome.ANSWERED))
        if (call !== ringing) return
        log.note("hermesCallAnswered", mapOf("settled" to settled.logLabel, "engine" to voice.engine.raw))
        // The phone got there first: the call is there. Declined there first,
        // the decline stands: the phone has already told Hermes.
        if (settled is HermesRingSettleResult.AlreadySettled && settled.by == HermesRingDevice.PHONE) {
            finish(ringing)
            return
        }
        // Or started while the settle went.
        if (voice.isActive) {
            finish(ringing)
            return
        }
        finish(ringing)
        // A settle that didn't reach the phone: the start does, and stops its ringing.
        voice.start("hermesCall", ring = ringing.ring.id)
    }

    // endregion

    // region Ending

    /** Decline, from the notification or the ringing screen: the phone stops ringing, and tells Hermes. */
    fun decline(id: String, done: () -> Unit = {}) {
        val ringing = call?.takeIf { it.ring.id == id && !it.answered }
        if (ringing == null) {
            done()
            return
        }
        log.note("hermesCallDeclined")
        noteSettled(id)
        finish(ringing)
        scope.launch {
            val settled = link.settleRing(WearMessage.RingSettle(id, HermesRingDevice.WATCH, HermesRingOutcome.DECLINED))
            log.note("hermesCallDeclineSettled", mapOf("settled" to settled.logLabel))
            done()
        }
    }

    private fun finish(ringing: Call) {
        if (call !== ringing) return
        call = null
        // Rung out, or ended any other way: the same call never rings again.
        noteSettled(ringing.ring.id)
        ringing.unanswered?.cancel()
        stopRingingOut()
    }

    private fun stopRingingOut() {
        _ringing.value = null
        vibrator?.cancel()
        manager.cancel(NOTIFICATION_ID)
    }

    // endregion

    // region Notification and haptics

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, L10n.tr("Calls from Hermes"), NotificationManager.IMPORTANCE_HIGH).apply {
            // The haptics are the call's own ([startHaptics]): a ringtone's pattern, until answered or declined.
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun postRinging(ring: WearMessage.Ring) {
        ensureChannel()
        val caller = Person.Builder().setName(ring.callerName).setKey("hermes").setImportant(true).setBot(true).build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_talk)
            .setContentTitle(ring.callerName)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent(context, ring.id), answerIntent(context, ring.id)))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(ringingScreenIntent(context, ring.id), true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(HermesWatchRingPlan.RINGS_FOR_MS + 5_000)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { log.note("hermesCallNotShown", mapOf("error" to it.javaClass.simpleName)) }
    }

    /** A ringtone's rhythm on the wrist, repeated; not while the watch is set to stay quiet. */
    private fun startHaptics() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator() || manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val effect = VibrationEffect.createWaveform(HAPTIC_PATTERN, 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            }
        }
    }

    // endregion

    companion object {
        const val CHANNEL = "hermes_calls"
        const val NOTIFICATION_ID = 7311
        const val ACTION_ANSWER = "app.hermesconduit.android.wear.HERMES_CALL_ANSWER"
        const val ACTION_DECLINE = "app.hermesconduit.android.wear.HERMES_CALL_DECLINE"
        const val EXTRA_RING = "ring"
        /** Off, buzz, off, buzz, pause: repeated from the start. */
        private val HAPTIC_PATTERN = longArrayOf(0, 500, 300, 500, 1_200)

        /** Opens the app on the answered call. */
        fun answerIntent(context: Context, ring: String): PendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_ANSWER)
                .putExtra(EXTRA_RING, ring)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun declineIntent(context: Context, ring: String): PendingIntent = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID,
            Intent(context, HermesCallReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_RING, ring),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun ringingScreenIntent(context: Context, ring: String): PendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID + 1,
            Intent(context, HermesCallActivity::class.java)
                .putExtra(EXTRA_RING, ring)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Decline from the ringing notification. */
class HermesCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HermesWatchCalls.ACTION_DECLINE) return
        val ring = intent.getStringExtra(HermesWatchCalls.EXTRA_RING) ?: return
        // The settle goes to the phone before the process may be stopped.
        val pending = goAsync()
        context.wearApp.hermesCalls.decline(ring) { pending.finish() }
    }
}

/** Wakes the watch app for the phone's rings and stops (WearWire.RING, RING_SETTLED). */
class HermesRingService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val message = WearWire.decode(event.path, event.data) ?: return
        val app = wearApp
        // The calls live on the main thread.
        app.scope.launch {
            when (message) {
                is WearMessage.Ring -> app.hermesCalls.receive(message)
                is WearMessage.RingSettled -> app.hermesCalls.settledOnPhone(message)
                else -> Unit
            }
        }
    }
}
