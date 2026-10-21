package app.hermesconduit.android.ui.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.R
import app.hermesconduit.android.core.L10n

internal object VoiceNotifications {
    const val CHANNEL = "conduit.voice"
    const val READ_ALOUD_ID = 7301
    const val CONVERSATION_ID = 7302

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, L10n.tr("Voice"), NotificationManager.IMPORTANCE_LOW))
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun builder(context: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
}

/**
 * Android's contract for `startForegroundService`: the service must call
 * `startForeground` once its start arrives, and must not be brought down
 * before then, or the system kills the app with "Context.startForegroundService()
 * did not then call Service.startForeground()". So a stop asked for while a
 * start is still on its way is held until the service has gone foreground,
 * and a service that can't keep its notification still enters the foreground
 * before it stops itself.
 */
internal class ForegroundStarts(private val service: Class<out Service>) {
    /** Starts requested that the service hasn't received yet. */
    @Volatile private var pending = 0
    @Volatile private var stopRequested = false

    fun start(context: Context, intent: Intent = Intent(context, service)): Boolean {
        intent.putExtra(EXTRA_FOREGROUND_START, true)
        pending++
        stopRequested = false
        val started = runCatching { context.startForegroundService(intent) }.isSuccess
        if (!started) pending = maxOf(0, pending - 1)
        return started
    }

    fun stop(context: Context) {
        if (pending > 0) stopRequested = true else context.stopService(Intent(context, service))
    }

    /** For `onStartCommand`: whether [intent] is a start that has to enter the foreground. */
    fun isForegroundStart(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_FOREGROUND_START, false) == true

    /** The service received [intent] and went foreground: true when it should stop now (a stop came while it was on its way). */
    fun delivered(intent: Intent?): Boolean {
        if (!isForegroundStart(intent)) return false
        pending = maxOf(0, pending - 1)
        if (pending > 0 || !stopRequested) return false
        stopRequested = false
        return true
    }

    private companion object {
        const val EXTRA_FOREGROUND_START = "conduit.foregroundStart"
    }
}

/**
 * Enters the foreground with the first of [types] the system allows; no
 * types (or before Android 10) uses the manifest's. Returns the type it went
 * foreground with (0 for the manifest's), or null when the system refused.
 */
internal fun Service.enterForeground(id: Int, notification: Notification, vararg types: Int): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || types.isEmpty()) return runCatching { startForeground(id, notification) }.map { 0 }.getOrNull()
    return types.firstOrNull { type -> runCatching { startForeground(id, notification, type) }.isSuccess }
}

/** Leaves the foreground and stops: after [enterForeground], so the system's start contract is kept. */
internal fun Service.stopForegroundAndSelf() {
    runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
    stopSelf()
}

/**
 * Read Aloud's media session and notification: lock screen, notification
 * shade and headset play/pause/stop, like iOS Now Playing (#373). A
 * mediaPlayback foreground service keeps the reply going in the background.
 */
class ReadAloudMediaSession(private val context: Context) : ReadAloudNowPlaying {
    private var session: MediaSession? = null
    private var commands: ReadAloudRemoteCommands? = null
    private var title = ""
    private var paused = false

    override fun begin(title: String, commands: ReadAloudRemoteCommands) {
        end()
        this.title = title
        this.commands = commands
        paused = false
        val media = MediaSession(context, "conduit.readAloud")
        media.setCallback(object : MediaSession.Callback() {
            override fun onPause() { commands.pause() }
            override fun onPlay() { commands.resume() }
            override fun onStop() { commands.stop() }
        })
        media.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, L10n.tr("Read Aloud"))
                .build(),
        )
        media.isActive = true
        session = media
        publishState()
        current = this
        if (!ReadAloudService.starts.start(context)) runCatching { context.startService(Intent(context, ReadAloudService::class.java)) }
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        publishState()
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(VoiceNotifications.READ_ALOUD_ID, notification()) }
    }

    override fun end() {
        val media = session ?: return
        session = null
        commands = null
        media.isActive = false
        media.release()
        if (current === this) current = null
        ReadAloudService.starts.stop(context)
    }

    private fun publishState() {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (paused) 0f else 1f)
                .build(),
        )
    }

    internal fun notification(): Notification {
        VoiceNotifications.ensureChannel(context)
        val action = { icon: Int, label: String, command: String ->
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, icon), label,
                PendingIntent.getService(
                    context, command.hashCode(), Intent(context, ReadAloudService::class.java).setAction(command),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()
        }
        val builder = VoiceNotifications.builder(context)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(L10n.tr("Read Aloud"))
            .setContentIntent(VoiceNotifications.openApp(context))
            .setOngoing(!paused)
            .addAction(
                if (paused) action(android.R.drawable.ic_media_play, L10n.tr("Resume reading response"), ACTION_RESUME)
                else action(android.R.drawable.ic_media_pause, L10n.tr("Pause"), ACTION_PAUSE),
            )
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, L10n.tr("Stop reading response"), ACTION_STOP))
        session?.let { builder.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1)) }
        return builder.build()
    }

    internal fun handle(action: String?) {
        val commands = commands ?: return
        when (action) {
            ACTION_PAUSE -> commands.pause()
            ACTION_RESUME -> commands.resume()
            ACTION_STOP -> commands.stop()
        }
    }

    companion object {
        const val ACTION_PAUSE = "conduit.readAloud.pause"
        const val ACTION_RESUME = "conduit.readAloud.resume"
        const val ACTION_STOP = "conduit.readAloud.stop"
        @Volatile internal var current: ReadAloudMediaSession? = null
    }
}

/** Keeps Read Aloud playing with the app in the background or the phone locked. */
class ReadAloudService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // The type applies from Android 10 only (enterForeground).
    @android.annotation.SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = ReadAloudMediaSession.current
        if (session == null) {
            // Read Aloud ended before its start arrived: go foreground anyway, then stop.
            if (starts.isForegroundStart(intent)) {
                VoiceNotifications.ensureChannel(this)
                val placeholder = VoiceNotifications.builder(this).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(L10n.tr("Read Aloud")).build()
                enterForeground(VoiceNotifications.READ_ALOUD_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                starts.delivered(intent)
                stopForegroundAndSelf()
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        intent?.action?.let { session.handle(it) }
        val entered = enterForeground(VoiceNotifications.READ_ALOUD_ID, session.notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (starts.delivered(intent) || entered == null) stopForegroundAndSelf()
        return START_NOT_STICKY
    }

    companion object {
        internal val starts = ForegroundStarts(ReadAloudService::class.java)
    }
}

/**
 * "Keep listening when locked" and live calls: a microphone foreground
 * service while a voice conversation is open, so capture keeps running
 * hands-free with the phone locked or Conduit in the background.
 */
class VoiceConversationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            VoiceHub.peek()?.let { hub ->
                hub.closeConversation()
                hub.liveCalls.closeActive()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        VoiceNotifications.ensureChannel(this)
        val end = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), L10n.tr("End"),
            PendingIntent.getService(this, 1, Intent(this, VoiceConversationService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val notification = VoiceNotifications.builder(this)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(L10n.tr("Voice conversation"))
            .setContentText(intent?.getStringExtra(EXTRA_MODE) ?: L10n.tr("Classic voice"))
            .setContentIntent(VoiceNotifications.openApp(this))
            .setOngoing(true)
            .addAction(end)
            .build()
        // Android 14+ refuses the microphone type without RECORD_AUDIO or with Conduit in the background:
        // the service then goes foreground for playback only, to keep the start contract, and stops.
        val microphone = hasMicrophonePermission(this)
        val types = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> intArrayOf()
            microphone -> intArrayOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else -> intArrayOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }
        val entered = enterForeground(VoiceNotifications.CONVERSATION_ID, notification, *types)
        val listens = entered != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || entered and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        if (starts.delivered(intent) || !listens) stopForegroundAndSelf()
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_END = "conduit.voice.end"
        const val EXTRA_MODE = "conduit.voice.mode"
        internal val starts = ForegroundStarts(VoiceConversationService::class.java)

        private fun hasMicrophonePermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /**
         * `mode` names a live call's mode in the notification; null is the classic conversation.
         * Nothing starts without the microphone permission: a microphone foreground service needs it.
         */
        fun start(context: Context, mode: String? = null) {
            if (!hasMicrophonePermission(context)) return
            val intent = Intent(context, VoiceConversationService::class.java)
            if (mode != null) intent.putExtra(EXTRA_MODE, mode)
            starts.start(context, intent)
        }

        fun stop(context: Context) = starts.stop(context)
    }
}
