package app.hermesconduit.android.ui.voice

import android.content.Context
import app.hermesconduit.android.ui.voice.live.LiveVoiceCalls

/**
 * Which voice surface a wake phrase or an answered call from Hermes opens
 * (iOS `openVoiceConversation` picks the profile's engine): the live call
 * screen when the profile uses a live mode, classic voice otherwise.
 */
object VoiceLaunch {
    const val CLASSIC_ROUTE = "voice"
    const val LIVE_ROUTE = LiveVoiceCalls.ROUTE

    fun route(context: Context): String =
        if (VoiceHub.get(context).liveCalls.enabledMode() != null) LIVE_ROUTE else CLASSIC_ROUTE
}
