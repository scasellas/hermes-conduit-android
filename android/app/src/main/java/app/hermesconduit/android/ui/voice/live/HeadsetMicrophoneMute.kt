package app.hermesconduit.android.ui.voice.live

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Which headset presses mute or unmute a live call. Pure, so the mapping
 * is tested without a media session.
 */
object HeadsetMuteKeys {
    /** The headset's call button, play/pause (a one-button headset, an earbud tap) and a mute key. */
    val toggleKeyCodes = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MUTE,
    )

    /** One press toggles once: on its first key down, never on the key up or a held key's repeats. */
    fun toggles(keyCode: Int, action: Int, repeatCount: Int): Boolean =
        keyCode in toggleKeyCodes && action == KeyEvent.ACTION_DOWN && repeatCount == 0
}

/**
 * The headset mute gesture during a live voice call (the iOS
 * `HeadsetMicrophoneMute`, #331). iOS has a system input mute that AirPods
 * toggle; Android has none, so the call holds a media session while it runs
 * and takes the headset's media button: a press toggles the call's own mute,
 * the same as the on-screen Mute button. There is no system state to keep in
 * step, so the on-screen button needs nothing from here. One call at a time
 * owns it; the call's end releases it.
 */
class HeadsetMicrophoneMute(private val context: Context) {
    private var session: MediaSession? = null

    /** Makes the running call the one a headset press mutes; `onToggle` runs on the main thread. */
    fun claim(onToggle: () -> Unit) {
        release()
        val media = runCatching { MediaSession(context, "conduit.liveCall") }.getOrNull() ?: return
        media.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = keyEvent(mediaButtonIntent) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                    if (event.keyCode !in HeadsetMuteKeys.toggleKeyCodes) return super.onMediaButtonEvent(mediaButtonIntent)
                    // The key up and repeats of a press are taken too, so they don't reach another player.
                    if (HeadsetMuteKeys.toggles(event.keyCode, event.action, event.repeatCount)) onToggle()
                    return true
                }
            },
            Handler(Looper.getMainLooper()),
        )
        // Media buttons go to the session that is playing: the call is.
        media.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        media.isActive = true
        session = media
    }

    /** Hands the media button back when the call ends. */
    fun release() {
        val media = session ?: return
        session = null
        media.isActive = false
        media.release()
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
}
