package app.hermesconduit.android.ui.kanban

import android.content.Context
import androidx.core.content.edit
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.KanbanEventInvalidation
import app.hermesconduit.android.model.KanbanLiveUpdatePolicy
import app.hermesconduit.android.model.KanbanPollingPolicy
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.CloudflareAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private class SharedPrefsKanbanPrefs(context: Context) : KanbanPrefs {
    private val prefs = context.getSharedPreferences("conduit_kanban", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }
}

data class KanbanSkillOption(val name: String, val description: String?)

/**
 * The single Kanban store for the process, shared by every Kanban screen, plus
 * the polling + live-events driver. Screens [attach] while started; the driver
 * runs while at least one is attached (backgrounding stops it).
 */
class KanbanRuntime private constructor(context: Context) {
    private val hermes: HermesConnection = context.hermes
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nudgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = KanbanStore(SharedPrefsKanbanPrefs(context)) { KanbanService(it, nudgeScope) }

    /** Show Archived: the board fetch parameter (shared by board and detail screens). */
    val includeArchived = MutableStateFlow(false)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var attachments = 0
    private var driver: Job? = null

    fun attach() {
        attachments++
        if (attachments == 1) driver = scope.launch { drive() }
    }

    fun detach() {
        attachments = (attachments - 1).coerceAtLeast(0)
        if (attachments == 0) {
            driver?.cancel()
            driver = null
        }
    }

    private suspend fun drive() {
        kotlinx.coroutines.coroutineScope {
            // Configure on connection identity changes, reload on Show Archived changes.
            launch {
                combine(hermes.connection.map { it?.id to it?.baseUrl }.distinctUntilChanged(), includeArchived) { conn, archived -> conn.second to archived }
                    .collectLatest { (base, archived) ->
                        val api = hermes.api
                        _connected.value = api != null && base != null
                        if (api == null || base == null) {
                            store.configure(null)
                        } else {
                            store.configure(HermesKanbanTransport(hermes), base, identity = api)
                        }
                        store.reload(archived)
                    }
            }
            // Polling fallback.
            launch {
                while (currentCoroutineContext().isActive) {
                    delay(KanbanPollingPolicy.BOARD_INTERVAL_MS)
                    store.poll(includeArchived.value)
                }
            }
            // Live events: one stream per (generation, loaded board, archived filter).
            launch {
                combine(store.state, includeArchived) { st, archived ->
                    LiveKey(st.configurationGeneration, st.loadedBoardSlug, archived, hermes.connection.value?.baseUrl)
                }.distinctUntilChanged().collectLatest { key -> runLiveStream(key) }
            }
        }
    }

    private data class LiveKey(val generation: Int, val board: String?, val archived: Boolean, val baseUrl: String?)

    private suspend fun runLiveStream(key: LiveKey) {
        val st = store.state.value
        val stamp = st.loadedContextStamp ?: return
        if (!st.isSelectedSnapshotLoaded || key.baseUrl == null) return
        val watermark = KanbanLiveUpdatePolicy.initialWatermark(st.board) ?: return
        val api = hermes.api ?: return
        val saved = hermes.connection.value
        val coordinator = KanbanEventStreamCoordinator(
            stamp = stamp,
            boardSlug = stamp.boardSlug,
            initialCursor = watermark,
            baseUrl = key.baseUrl,
            scope = scope,
            socketFactory = { url ->
                OkHttpKanbanEventSocket.open(
                    api.http, url,
                    CloudflareAccess.of(saved?.cfClientId, saved?.cfClientSecret),
                    saved?.customHeaders.orEmpty(),
                )
            },
            ticketMinter = { hermes.rest { it.webSocketTicket() } },
            onBatch = { invalidation -> handleBatch(invalidation, key.archived) },
        )
        coordinator.start()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            coordinator.stop()
        }
    }

    private suspend fun handleBatch(invalidation: KanbanEventInvalidation, archived: Boolean): KanbanEventBatchOutcome =
        when (store.refreshFromEvent(invalidation.context, archived)) {
            KanbanStore.EventRefreshDisposition.REFRESHED -> {
                store.publishLiveInvalidation(invalidation)
                KanbanEventBatchOutcome.COMPLETED
            }
            KanbanStore.EventRefreshDisposition.DEFERRED -> KanbanEventBatchOutcome.RETRY_SOON
            KanbanStore.EventRefreshDisposition.STALE -> KanbanEventBatchOutcome.DISCARD
        }

    /** The active profile's installed skills (`GET /api/skills`), for the composer's picker. */
    suspend fun fetchSkills(): List<KanbanSkillOption> {
        val profile = hermes.profile.value
        val result = hermes.rest { it.getJson("api/skills", mapOf("profile" to profile.takeIf { p -> p != "default" })) }
        val rows = result["_array"].arr ?: result["skills"].arr ?: return emptyList()
        return rows.mapNotNull { row ->
            val o = row.obj ?: return@mapNotNull null
            val name = o["name"].str ?: return@mapNotNull null
            KanbanSkillOption(name, o["description"].str)
        }.sortedBy { it.name.lowercase() }
    }

    companion object {
        @Volatile private var instance: KanbanRuntime? = null

        fun get(context: Context): KanbanRuntime =
            instance ?: synchronized(this) { instance ?: KanbanRuntime(context.applicationContext).also { instance = it } }
    }
}
