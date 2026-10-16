package app.hermesconduit.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** What the app needs to reconnect to a dashboard without asking again. */
data class SavedConnection(
    val baseUrl: String,
    val username: String,
    val password: String,
    val profile: String = "default",
    val cfClientId: String? = null,
    val cfClientSecret: String? = null,
)

/**
 * Credential storage backed by EncryptedSharedPreferences (Android Keystore),
 * the counterpart of the iOS Keychain entries.
 */
@Suppress("DEPRECATION") // security-crypto is deprecated but still the simplest Keystore-backed store.
class ConnectionStore(context: Context) {
    private val prefs: SharedPreferences = try {
        open(context)
    } catch (_: Exception) {
        // A key lost from the Keystore (e.g. after a restore) leaves the file
        // unreadable; start over rather than crash at launch.
        context.deleteSharedPreferences(FILE)
        open(context)
    }

    private fun open(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): SavedConnection? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        return SavedConnection(
            baseUrl = baseUrl,
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            profile = prefs.getString(KEY_PROFILE, "default") ?: "default",
            cfClientId = prefs.getString(KEY_CF_ID, null),
            cfClientSecret = prefs.getString(KEY_CF_SECRET, null),
        )
    }

    fun save(connection: SavedConnection) = prefs.edit {
        putString(KEY_BASE_URL, connection.baseUrl)
        putString(KEY_USERNAME, connection.username)
        putString(KEY_PASSWORD, connection.password)
        putString(KEY_PROFILE, connection.profile)
        putString(KEY_CF_ID, connection.cfClientId)
        putString(KEY_CF_SECRET, connection.cfClientSecret)
    }

    fun saveProfile(profile: String) = prefs.edit { putString(KEY_PROFILE, profile) }

    /** Last base URL entered, kept after sign-out to prefill the form. */
    fun lastBaseUrl(): String? = prefs.getString(KEY_LAST_BASE_URL, null)

    var cookies: String?
        get() = prefs.getString(KEY_COOKIES, null)
        set(value) = prefs.edit { putString(KEY_COOKIES, value) }

    fun clear() {
        val last = prefs.getString(KEY_BASE_URL, null)
        prefs.edit {
            clear()
            if (last != null) putString(KEY_LAST_BASE_URL, last)
        }
    }

    private companion object {
        const val FILE = "conduit_connection"
        const val KEY_BASE_URL = "base_url"
        const val KEY_LAST_BASE_URL = "last_base_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_PROFILE = "profile"
        const val KEY_CF_ID = "cf_client_id"
        const val KEY_CF_SECRET = "cf_client_secret"
        const val KEY_COOKIES = "cookies"
    }
}
