package app.hermesconduit.android.wake

import android.content.Context
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.WakeModelDescriptor
import app.hermesconduit.android.model.WakeTokenizationTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The sherpa-onnx keyword model on this device (iOS WakeModelPackResolving).
 * Never bundled: downloaded from the descriptor's source URL on the user's
 * request, checked against the descriptor's archive SHA-256, and unpacked
 * to the five assets the descriptor lists.
 */
class WakeModelStore(private val context: Context, val descriptor: WakeModelDescriptor = WakeModelDescriptor.bundledBilingualPack) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float?) : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    private val directory: File get() = File(context.filesDir, "wake/" + descriptor.id)

    private val _state = MutableStateFlow(if (isInstalled()) State.Installed else State.Missing)
    val state: StateFlow<State> = _state

    fun isInstalled(): Boolean = descriptor.assets.all { File(directory, it.relativePath).isFile }

    /** The installed pack's folder, or null (iOS `localURL(for:)`). */
    fun localDirectory(): File? = directory.takeIf { isInstalled() }

    fun file(relativePath: String) = File(directory, relativePath)

    /** The English lexicon of the installed pack, as compiler tables. */
    fun tables(): WakeTokenizationTables =
        localDirectory()?.let { dir -> File(dir, descriptor.lexicon).bufferedReader().useLines { WakeTokenizationTables.fromLexicon(it) } }
            ?: WakeTokenizationTables.unavailableProductionTables

    suspend fun download() = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading) return@withContext
        _state.value = State.Downloading(null)
        val archive = File(context.cacheDir, "wake-model.tar.bz2")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL(descriptor.sourceURL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong.takeIf { it > 0 }
                var read = 0L
                connection.inputStream.use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            read += n
                            _state.value = State.Downloading(total?.let { read.toFloat() / it })
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != descriptor.archiveSHA256) throw IllegalStateException("checksum")
            extract(archive)
            _state.value = if (isInstalled()) State.Installed else State.Failed(L10n.tr("The wake model download was incomplete. Try again."))
        } catch (e: Exception) {
            _state.value = State.Failed(
                if (e.message == "checksum") L10n.tr("The downloaded wake model didn't match its checksum, so it wasn't installed.")
                else L10n.tr("Couldn't download the wake model. Check your connection and try again."),
            )
        } finally {
            archive.delete()
        }
    }

    private fun extract(archive: File) {
        val wanted = descriptor.assets.map { it.relativePath }.toSet()
        val staging = File(directory.parentFile, descriptor.id + ".partial").apply { deleteRecursively(); mkdirs() }
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wanted) continue
                File(staging, name).outputStream().use { tar.copyTo(it) }
            }
        }
        directory.deleteRecursively()
        staging.renameTo(directory)
    }

    fun remove() {
        directory.deleteRecursively()
        _state.value = State.Missing
    }
}
