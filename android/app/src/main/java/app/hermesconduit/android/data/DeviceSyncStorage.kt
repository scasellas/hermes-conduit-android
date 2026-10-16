package app.hermesconduit.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Where health, calendar & phone and location sharing keep what they
 * remember: state files in the app's no-backup folder (anchors, hashes,
 * cursors: never the data itself) and the agreed keys in
 * EncryptedSharedPreferences (Android Keystore), the iOS Keychain's place.
 */
interface TextStore {
    /** Nil when nothing is saved. */
    fun load(): String?
    /** False when it couldn't be written. */
    fun save(text: String?): Boolean
}

class FileTextStore(private val file: File) : TextStore {
    override fun load(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()

    override fun save(text: String?): Boolean = runCatching {
        if (text == null) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }
        true
    }.getOrDefault(false)

    companion object {
        fun of(context: Context, folder: String, name: String = "state.json") =
            FileTextStore(File(File(context.noBackupFilesDir, folder), name))
    }
}

/** In memory, for tests and as a fallback. */
class MemoryTextStore(private var value: String? = null) : TextStore {
    override fun load(): String? = value
    override fun save(text: String?): Boolean {
        value = text
        return true
    }
}

/** The agreed keys, by account name. */
object DeviceKeyStore {
    @Volatile private var prefs: SharedPreferences? = null

    private fun open(context: Context): SharedPreferences = prefs ?: synchronized(this) {
        prefs ?: runCatching {
            EncryptedSharedPreferences.create(
                context.applicationContext,
                "conduit_device_keys",
                MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { context.applicationContext.getSharedPreferences("conduit_device_keys_plain", Context.MODE_PRIVATE) }
            .also { prefs = it }
    }

    fun store(context: Context, account: String): TextStore = object : TextStore {
        override fun load(): String? = runCatching { open(context).getString(account, null) }.getOrNull()
        override fun save(text: String?): Boolean = runCatching {
            open(context).edit(commit = true) { if (text == null) remove(account) else putString(account, text) }
            true
        }.getOrDefault(false)
    }
}

/** A preference string through [AppSettings] (no health or calendar data in them). */
class SettingsTextStore(private val settings: AppSettings, private val key: String) : TextStore {
    override fun load(): String? = settings.getString(key, "").ifEmpty { null }
    override fun save(text: String?): Boolean {
        settings.set(key, text.orEmpty())
        return true
    }
}
