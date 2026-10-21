package app.hermesconduit.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** One saved Hermes dashboard and what's needed to reconnect without asking. */
@Serializable
data class SavedConnection(
    val baseUrl: String,
    val username: String,
    val password: String = "",
    val profile: String = "default",
    val cfClientId: String? = null,
    val cfClientSecret: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val name: String? = null,
    /** "password" or "oauth". */
    val authMode: String = "password",
    val oauthAccessToken: String? = null,
    val oauthRefreshToken: String? = null,
    val oauthExpiresAt: Long? = null,
    val oauthProvider: String? = null,
    /** Extra request headers bound to this dashboard (HTTPS only). */
    val customHeaders: Map<String, String> = emptyMap(),
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: baseUrl.substringAfter("://")
}

/**
 * Credential storage backed by EncryptedSharedPreferences (Android Keystore),
 * the counterpart of the iOS Keychain entries. Holds every saved dashboard
 * (the iOS SavedDashboardRegistry) plus each one's session cookies.
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

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(SavedConnection.serializer())

    private fun open(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun all(): List<SavedConnection> {
        migrateLegacy()
        val raw = prefs.getString(KEY_DASHBOARDS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun writeAll(list: List<SavedConnection>) = prefs.edit { putString(KEY_DASHBOARDS, json.encodeToString(listSerializer, list)) }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit { putString(KEY_ACTIVE, value) }

    fun load(): SavedConnection? {
        val list = all()
        return list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
    }

    /** Saves (insert or replace by id) and makes it active. */
    fun save(connection: SavedConnection) {
        val list = all().filterNot { it.id == connection.id || (it.baseUrl == connection.baseUrl && it.username == connection.username) }
        writeAll(list + connection)
        activeId = connection.id
    }

    fun update(connection: SavedConnection) {
        writeAll(all().map { if (it.id == connection.id) connection else it })
    }

    fun remove(id: String) {
        writeAll(all().filterNot { it.id == id })
        prefs.edit { remove(KEY_COOKIES_PREFIX + id) }
        if (activeId == id) activeId = all().firstOrNull()?.id
    }

    fun cookies(id: String): String? = prefs.getString(KEY_COOKIES_PREFIX + id, null)

    fun saveCookies(id: String, value: String) = prefs.edit { putString(KEY_COOKIES_PREFIX + id, value) }

    /** Last base URL entered, kept after sign-out to prefill the form. */
    var lastBaseUrl: String?
        get() = prefs.getString(KEY_LAST_BASE_URL, null)
        set(value) = prefs.edit { putString(KEY_LAST_BASE_URL, value) }

    /** The single-dashboard layout of the first Android release. */
    private fun migrateLegacy() {
        val baseUrl = prefs.getString("base_url", null) ?: return
        val legacy = SavedConnection(
            baseUrl = baseUrl,
            username = prefs.getString("username", "").orEmpty(),
            password = prefs.getString("password", "").orEmpty(),
            profile = prefs.getString("profile", "default") ?: "default",
            cfClientId = prefs.getString("cf_client_id", null),
            cfClientSecret = prefs.getString("cf_client_secret", null),
        )
        val cookies = prefs.getString("cookies", null)
        prefs.edit {
            listOf("base_url", "username", "password", "profile", "cf_client_id", "cf_client_secret", "cookies").forEach { remove(it) }
            putString(KEY_DASHBOARDS, json.encodeToString(listSerializer, listOf(legacy)))
            putString(KEY_ACTIVE, legacy.id)
            if (cookies != null) putString(KEY_COOKIES_PREFIX + legacy.id, cookies)
        }
    }

    private companion object {
        const val FILE = "conduit_connection"
        const val KEY_DASHBOARDS = "dashboards_v2"
        const val KEY_ACTIVE = "active_dashboard"
        const val KEY_COOKIES_PREFIX = "cookies_"
        const val KEY_LAST_BASE_URL = "last_base_url"
    }
}
