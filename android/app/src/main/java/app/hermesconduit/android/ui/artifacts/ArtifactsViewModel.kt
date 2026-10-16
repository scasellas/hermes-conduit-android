package app.hermesconduit.android.ui.artifacts

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.ArtifactFilter
import app.hermesconduit.android.model.ArtifactItem
import app.hermesconduit.android.model.ArtifactParser
import app.hermesconduit.android.ui.gateway.requestJson
import app.hermesconduit.android.ui.gateway.userMessage
import app.hermesconduit.android.ui.plugin.PluginStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ArtifactsState(
    /** Null until the plugin was asked. */
    val supported: Boolean? = null,
    val filter: ArtifactFilter = ArtifactFilter.ALL,
    val query: String = "",
    val items: List<ArtifactItem> = emptyList(),
    val nextBefore: Long? = null,
    val loading: Boolean = false,
    /** The (filter, query) the items are for; null before the first answer. */
    val loadedKey: Pair<ArtifactFilter, String>? = null,
    val failure: String? = null,
    val openingId: String? = null,
)

/** One artifacts list: the profile's, or one chat's (`sessionId`). */
class ArtifactsViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes
    private val plugin = PluginStatusRepository.of(hermes)
    private val _state = MutableStateFlow(ArtifactsState())
    val state: StateFlow<ArtifactsState> = _state
    private var sessionId: String? = null
    private var started = false
    private var loadJob: Job? = null
    private val thumbnails = LruCache<String, Bitmap>(120)

    fun start(sessionId: String?) {
        if (started) return
        started = true
        this.sessionId = sessionId
        viewModelScope.launch {
            val supported = runCatching { plugin.supports(ArtifactParser.CAPABILITY) }.getOrDefault(false)
            _state.update { it.copy(supported = supported) }
            if (supported) reload()
        }
    }

    private fun key(s: ArtifactsState = _state.value) = s.filter to s.query.trim()

    fun setFilter(filter: ArtifactFilter) {
        _state.update { it.copy(filter = filter) }
        reload()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        // Typing settles before the host is asked.
        reload(debounceMs = 350)
    }

    fun reload(debounceMs: Long = 0) {
        if (_state.value.supported != true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val key = key()
            val profile = hermes.profile.value
            _state.update { it.copy(loading = true) }
            try {
                val page = fetch(profile, key.first, key.second, null)
                _state.update { it.copy(items = page.items, nextBefore = page.nextBefore, failure = null, loadedKey = key, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed refresh keeps the list it was refreshing.
                _state.update {
                    if (it.loadedKey == key) it.copy(loading = false)
                    else it.copy(failure = e.userMessage(), items = emptyList(), nextBefore = null, loadedKey = key, loading = false)
                }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val before = s.nextBefore ?: return
        if (s.loading || s.loadedKey != key(s)) return
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val page = fetch(hermes.profile.value, s.filter, s.query.trim(), before)
                _state.update { it.copy(items = ArtifactParser.merged(it.items, page.items), nextBefore = page.nextBefore, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(failure = e.userMessage(), loading = false) }
            }
        }
    }

    fun retryMore() {
        _state.update { it.copy(failure = null) }
        loadMore()
    }

    private suspend fun fetch(profile: String, filter: ArtifactFilter, query: String, before: Long?) =
        ArtifactParser.page(
            // A long history takes the host a moment to read.
            hermes.requestJson("GET", ArtifactParser.PATH, ArtifactParser.query(profile, sessionId, filter, query, before), timeoutSeconds = 30),
        ) ?: throw IllegalStateException("The gateway returned an invalid response.")

    // region Files

    suspend fun thumbnail(item: ArtifactItem): Bitmap? {
        val path = item.path ?: return null
        if (item.kind != ArtifactItem.Kind.IMAGE || !item.exists) return null
        val cacheKey = hermes.profile.value + "\u0000" + path
        thumbnails.get(cacheKey)?.let { return it }
        val bitmap = runCatching {
            val file = HostFiles.read(hermes, path)
            withContext(Dispatchers.Default) { HostFiles.decodeBitmap(file.bytes, 360) }
        }.getOrNull() ?: return null
        thumbnails.put(cacheKey, bitmap)
        return bitmap
    }

    /** Reads an artifact's file, marking it as opening meanwhile. */
    suspend fun read(item: ArtifactItem): HostFile? {
        val path = item.path ?: return null
        if (_state.value.openingId != null) return null
        _state.update { it.copy(openingId = item.id) }
        return try {
            HostFiles.read(hermes, path)
        } finally {
            _state.update { it.copy(openingId = null) }
        }
    }

    // endregion
}
