package app.hermesconduit.android.ui.voice

import android.content.Context

/**
 * Which voice surface a wake phrase or an answered call from Hermes opens
 * (iOS `openVoiceConversation` picks the profile's engine). Classic voice
 * for now: when a live mode is ported, return its route here for profiles
 * that use it, and both entry points follow.
 */
object VoiceLaunch {
    const val CLASSIC_ROUTE = "voice"

    @Suppress("UNUSED_PARAMETER")
    fun route(context: Context): String = CLASSIC_ROUTE
}
