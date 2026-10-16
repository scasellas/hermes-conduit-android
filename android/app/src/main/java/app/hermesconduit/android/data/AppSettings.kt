package app.hermesconduit.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Non-secret app preferences (the iOS `@AppStorage` keys), observable so
 * screens update when another surface changes them.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("conduit_settings", Context.MODE_PRIVATE)
    private val flows = mutableMapOf<String, MutableStateFlow<Any?>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(key: String, read: () -> T): MutableStateFlow<T> =
        synchronized(flows) { flows.getOrPut(key) { MutableStateFlow(read()) } as MutableStateFlow<T> }

    fun bool(key: String, default: Boolean): StateFlow<Boolean> = flow(key) { prefs.getBoolean(key, default) }
    fun string(key: String, default: String): StateFlow<String> = flow(key) { prefs.getString(key, default) ?: default }
    fun float(key: String, default: Float): StateFlow<Float> = flow(key) { prefs.getFloat(key, default) }
    fun int(key: String, default: Int): StateFlow<Int> = flow(key) { prefs.getInt(key, default) }

    fun getBool(key: String, default: Boolean) = bool(key, default).value
    fun getString(key: String, default: String) = string(key, default).value

    fun set(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
        flow(key) { value }.value = value
    }

    fun set(key: String, value: String) {
        prefs.edit { putString(key, value) }
        flow(key) { value }.value = value
    }

    fun set(key: String, value: Float) {
        prefs.edit { putFloat(key, value) }
        flow(key) { value }.value = value
    }

    fun set(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
        flow(key) { value }.value = value
    }

    companion object {
        const val LANGUAGE = "app_language"
        const val THEME = "theme_mode" // system | light | dark
        const val BIOMETRIC_LOCK = "biometric_lock"
        const val HAPTICS = "haptics_enabled"
        const val SHOW_REASONING = "show_reasoning"
        const val SHOW_TOOL_CALLS = "show_tool_calls"
        const val SEND_ON_ENTER = "send_on_enter"
        const val CHAT_FONT_SCALE = "chat_font_scale"
        const val NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val BACKGROUND_CONNECTION = "background_connection"
        const val CHAT_RESUME = "chat_resume" // continue | latest
        const val CHAT_TEXT_SIZE = "chat_text_size" // 0…4
        const val ATTACHMENT_MAX_MB = "attachment_max_mb"
        const val AUTO_TAKEOVER = "auto_takeover"
        const val LAST_SESSION = "last_session"
        const val APP_ICON = "app_icon" // dark | light

        fun displayNameKey(dashboardId: String?, profile: String) = "display_name.${dashboardId ?: "-"}.$profile"
        const val CAPABILITIES_TAB = "capabilities.tab"
        const val CAPABILITIES_SKILLS_MOST_USED = "capabilities.skills.mostUsedFirst"
        const val CAPABILITIES_TOOLS_MOST_USED = "capabilities.tools.mostUsedFirst"
        const val KANBAN_GROUP_RUNNING = "conduit.kanbanGroupRunningByProfile"
    }
}
