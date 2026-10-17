package app.hermesconduit.android.car

import app.hermesconduit.android.data.AppSettings

/**
 * On-device Android Auto settings (iOS CarPlayPreferences), edited in
 * Settings > Android Auto on the phone: whether the voice screen plays its
 * status sounds, and whether the car opens on the chat list. Device-wide,
 * like the car itself.
 */
object CarPreferences {
    const val SOUNDS_KEY = "conduit.androidauto.sounds.v1"
    const val CHOOSE_CHAT_FIRST_KEY = "conduit.androidauto.chooseChatFirst.v1"

    /** Status sounds are on unless the driver turns them off. */
    const val SOUNDS_DEFAULT = true

    /**
     * The car opens on the chat list (New voice chat, pinned, recent) instead
     * of starting Voice at once. Off unless the driver turns it on: a voice
     * app opens voice first, and the voice screen's Chats button reaches the
     * list in one tap.
     */
    const val CHOOSE_CHAT_FIRST_DEFAULT = false

    fun playsSounds(settings: AppSettings) = settings.getBool(SOUNDS_KEY, SOUNDS_DEFAULT)

    fun choosesChatFirst(settings: AppSettings) = settings.getBool(CHOOSE_CHAT_FIRST_KEY, CHOOSE_CHAT_FIRST_DEFAULT)
}
