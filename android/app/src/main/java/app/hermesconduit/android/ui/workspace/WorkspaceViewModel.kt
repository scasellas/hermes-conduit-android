package app.hermesconduit.android.ui.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.WorkspaceEntry
import app.hermesconduit.android.model.WorkspaceFilePreview
import app.hermesconduit.android.model.WorkspaceParser
import app.hermesconduit.android.ui.artifacts.HostFile
import app.hermesconduit.android.ui.artifacts.HostFiles
import app.hermesconduit.android.ui.gateway.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceState(
    val root: String = "",
    val entries: Map<String, List<WorkspaceEntry>> = emptyMap(),
    val expanded: Set<String> = emptySet(),
    val loadingPath: String? = null,
    val error: String? = null,
    val selected: WorkspaceEntry? = null,
    val preview: WorkspaceFilePreview? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val downloading: Boolean = false,
)

/** The workspace browser (iOS `WorkspaceBrowserSheet` + AppState workspace calls). */
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes
    private val settings = application.appSettings
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state
    private var started = false

    fun start(rootArg: String?) {
        if (started) return
        started = true
        val root = rootArg?.takeIf { it.isNotBlank() } ?: settings.getString(LAST_ROOT, "")
        if (root.isNotBlank()) open(root)
    }

    private val profile get() = hermes.profile.value

    /** Opens a folder on the host as the browser's root. */
    fun open(root: String) {
        val path = root.trim()
        if (path.isEmpty()) return
        settings.set(LAST_ROOT, path)
        _state.value = WorkspaceState(root = path)
        viewModelScope.launch { load(path) }
    }

    fun refresh() {
        val root = _state.value.root
        if (root.isEmpty()) return
        _state.update { it.copy(entries = emptyMap(), expanded = emptySet()) }
        viewModelScope.launch { load(root) }
    }

    fun toggle(entry: WorkspaceEntry) {
        if (!entry.isDirectory) return
        val s = _state.value
        if (entry.path in s.expanded) {
            _state.update { it.copy(expanded = it.expanded - entry.path) }
            return
        }
        _state.update { it.copy(expanded = it.expanded + entry.path) }
        if (s.entries[entry.path] != null) return
        viewModelScope.launch {
            if (!load(entry.path)) _state.update { it.copy(expanded = it.expanded - entry.path) }
        }
    }

    private suspend fun load(path: String): Boolean {
        _state.update { it.copy(loadingPath = path, error = null) }
        return try {
            val result = hermes.rest { it.getJson("api/fs/list", mapOf("path" to path, "profile" to profile.takeIf { p -> p != "default" })) }
            val entries = WorkspaceParser.entries(result)
            _state.update { it.copy(entries = it.entries + (path to entries)) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.userMessage()) }
            false
        } finally {
            _state.update { if (it.loadingPath == path) it.copy(loadingPath = null) else it }
        }
    }

    fun preview(entry: WorkspaceEntry) {
        _state.update { it.copy(selected = entry, preview = null, previewError = null, previewLoading = true) }
        viewModelScope.launch {
            try {
                val result = hermes.rest {
                    it.getJson("api/fs/read-text", mapOf("path" to entry.path, "profile" to profile.takeIf { p -> p != "default" }))
                }
                _state.update { if (it.selected == entry) it.copy(preview = WorkspaceParser.preview(result)) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(previewError = e.userMessage()) }
            } finally {
                _state.update { it.copy(previewLoading = false) }
            }
        }
    }

    fun closePreview() = _state.update { it.copy(selected = null, preview = null, previewError = null) }

    /** Reads the selected file's bytes for saving or sharing. */
    suspend fun download(entry: WorkspaceEntry): HostFile? {
        _state.update { it.copy(downloading = true) }
        return try {
            HostFiles.read(hermes, entry.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(previewError = e.userMessage()) }
            null
        } finally {
            _state.update { it.copy(downloading = false) }
        }
    }

    fun reportError(message: String) = _state.update { it.copy(previewError = message) }

    companion object {
        /** The folder the browser opened last (per device). */
        const val LAST_ROOT = "workspace_last_root"
    }
}
