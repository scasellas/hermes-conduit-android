package app.hermesconduit.android.ui.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
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
        runCatching { context.startForegroundService(Intent(context, ReadAloudService::class.java)) }
            .onFailure { runCatching { context.startService(Intent(context, ReadAloudService::class.java)) } }
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
        context.stopService(Intent(context, ReadAloudService::class.java))
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = ReadAloudMediaSession.current
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.action?.let { session.handle(it) }
        val notification = session.notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { startForeground(VoiceNotifications.READ_ALOUD_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        } else {
            runCatching { startForeground(VoiceNotifications.READ_ALOUD_ID, notification) }
        }
        return START_NOT_STICKY
    }
}

/**
 * "Keep listening when locked": a microphone foreground service while a
 * classic voice conversation is open, so capture keeps running hands-free
 * with the phone locked or Conduit in the background.
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
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(VoiceNotifications.CONVERSATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(VoiceNotifications.CONVERSATION_ID, notification)
            }
        }
        if (started.isFailure) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_END = "conduit.voice.end"
        const val EXTRA_MODE = "conduit.voice.mode"

        /** `mode` names a live call's mode in the notification; null is the classic conversation. */
        fun start(context: Context, mode: String? = null) {
            val intent = Intent(context, VoiceConversationService::class.java)
            if (mode != null) intent.putExtra(EXTRA_MODE, mode)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceConversationService::class.java))
        }
    }
}
