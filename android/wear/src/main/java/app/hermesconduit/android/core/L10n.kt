package app.hermesconduit.android.core

/**
 * The lookup the shared live voice code (android/livewire) calls, as
 * `core.L10n` in the phone app: on the watch it reads the Wear OS app's own
 * tables (`wear.L10n`, from the iOS Watch app's String Catalog).
 */
object L10n {
    fun tr(key: String): String = app.hermesconduit.android.wear.L10n.tr(key)

    fun tr(key: String, vararg args: Any?): String = app.hermesconduit.android.wear.L10n.tr(key, *args)
}
