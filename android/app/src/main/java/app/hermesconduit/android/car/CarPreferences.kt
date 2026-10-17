package app.hermesconduit.android.car

import app.hermesconduit.android.data.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * A prompt the driver starts with one tap (iOS CarPlayShortcut). It runs as
 * a Voice Job, and the result is spoken in the conversation the tap opens.
 */
data class CarShortcut(val id: String = UUID.randomUUID().toString(), val title: String, val prompt: String)

/**
 * On-device Android Auto settings (iOS CarPlayPreferences), edited in
 * Settings > Android Auto on the phone: the shortcuts the car's Shortcuts
 * grid offers, whether the voice screen plays its status sounds, and whether
 * the car opens on the chat list. Device-wide, like the car itself.
 */
object CarPreferences {
    const val SHORTCUTS_KEY = "conduit.androidauto.shortcuts.v1"
    const val SOUNDS_KEY = "conduit.androidauto.sounds.v1"
    const val CHOOSE_CHAT_FIRST_KEY = "conduit.androidauto.chooseChatFirst.v1"

    /** The car's Shortcuts grid shows at most eight, as CarPlay's does. */
    const val MAXIMUM_SHORTCUTS = 8

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

    fun shortcuts(settings: AppSettings): List<CarShortcut> = CarShortcuts.decode(settings.getString(SHORTCUTS_KEY, ""))

    /** Adds a shortcut, or replaces the one with the same id. False when it was refused (blank, or the grid is full). */
    fun save(settings: AppSettings, shortcut: CarShortcut): Boolean {
        val updated = CarShortcuts.saving(shortcuts(settings), shortcut) ?: return false
        settings.set(SHORTCUTS_KEY, CarShortcuts.encode(updated))
        return true
    }

    fun delete(settings: AppSettings, id: String) {
        settings.set(SHORTCUTS_KEY, CarShortcuts.encode(shortcuts(settings).filter { it.id != id }))
    }
}

/** The shortcut list's rules and storage format, testable without a device. */
object CarShortcuts {
    fun canAdd(shortcuts: List<CarShortcut>) = shortcuts.size < CarPreferences.MAXIMUM_SHORTCUTS

    /**
     * The list with [shortcut] added, or replacing the one with its id; null
     * when its title or prompt is blank, or when a new one meets a full grid.
     */
    fun saving(shortcuts: List<CarShortcut>, shortcut: CarShortcut): List<CarShortcut>? {
        val cleaned = shortcut.copy(title = shortcut.title.trim(), prompt = shortcut.prompt.trim())
        if (cleaned.title.isEmpty() || cleaned.prompt.isEmpty()) return null
        val index = shortcuts.indexOfFirst { it.id == cleaned.id }
        if (index >= 0) return shortcuts.toMutableList().also { it[index] = cleaned }
        if (!canAdd(shortcuts)) return null
        return shortcuts + cleaned
    }

    fun encode(shortcuts: List<CarShortcut>): String = buildJsonArray {
        for (shortcut in shortcuts) {
            add(buildJsonObject {
                put("id", shortcut.id)
                put("title", shortcut.title)
                put("prompt", shortcut.prompt)
            })
        }
    }.toString()

    /** Stored shortcuts, at most the grid's eight; anything unreadable is dropped. */
    fun decode(stored: String): List<CarShortcut> {
        if (stored.isBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(stored) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            fun field(name: String) = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = field("id") ?: return@mapNotNull null
            val title = field("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val prompt = field("prompt")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CarShortcut(id, title, prompt)
        }.take(CarPreferences.MAXIMUM_SHORTCUTS)
    }
}
