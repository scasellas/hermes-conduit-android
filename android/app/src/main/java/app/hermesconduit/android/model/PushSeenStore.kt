package app.hermesconduit.android.model

import app.hermesconduit.android.data.TextStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sealed pushes already handled (iOS E2ESeenStore): a second delivery of the
 * same message is a replay. By namespace: "delivered" (a notification was
 * shown), "rang" (a call rang), and "locate" / "maps" for requests that are
 * claimed while they're answered, so a redelivery during the answer stops,
 * and released if the answer never reached the host. Entries older than
 * twice the envelope's freshness window are pruned: the envelope check
 * rejects anything that old already.
 */
class PushSeenStore(private val store: TextStore) {
    private val lock = Any()

    /** Records `key` in `namespace`; false if it was already there. */
    fun insert(key: String, namespace: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val entries = pruned(load(), nowMs)
        val entry = "$namespace:$key"
        if (entry in entries) {
            save(entries)
            return false
        }
        entries[entry] = nowMs
        trim(entries)
        save(entries)
        true
    }

    /** Claims `key` for work that may not finish. False while it's answered, or claimed less than `ttlMs` ago. */
    fun claim(key: String, namespace: String, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val entries = load()
        if ("$namespace-answered:$key" in entries) return false
        val entry = "$namespace:$key"
        entries[entry]?.let { if (nowMs - it < ttlMs) return false }
        val next = pruned(entries, nowMs)
        next[entry] = nowMs
        trim(next)
        save(next)
        true
    }

    /** The claimed work is done: `key` stays a replay from now on. */
    fun markAnswered(key: String, namespace: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val entries = load()
        entries["$namespace-answered:$key"] = nowMs
        save(entries)
    }

    /** Forgets `key`, so a redelivery can try again after the work failed. */
    fun remove(key: String, namespace: String) = synchronized(lock) {
        val entries = load()
        if (entries.remove("$namespace:$key") != null) save(entries)
    }

    private fun pruned(entries: MutableMap<String, Long>, nowMs: Long): MutableMap<String, Long> {
        val cutoff = nowMs - RETENTION_MS
        return entries.filterValues { it >= cutoff }.toMutableMap()
    }

    private fun trim(entries: MutableMap<String, Long>) {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries.sortedBy { it.value }.take(entries.size - MAX_ENTRIES).map { it.key }.forEach(entries::remove)
    }

    private fun load(): MutableMap<String, Long> {
        val json = store.load()?.let { runCatching { ConduitJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return mutableMapOf()
        return json.entries.mapNotNull { (k, v) -> v.long?.let { k to it } }.toMap().toMutableMap()
    }

    private fun save(entries: Map<String, Long>) {
        store.save(JsonObject(entries.mapValues { JsonPrimitive(it.value) }).toString())
    }

    companion object {
        const val RETENTION_MS = 2 * PushE2E.MAX_AGE_MS
        const val MAX_ENTRIES = 4096
    }
}
