package app.hermesconduit.android.ui.artifacts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.model.WorkspaceParser
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file read from the Hermes host. */
class HostFile(val name: String, val bytes: ByteArray, val mimeType: String?)

/**
 * Files on the Hermes host, read through the authenticated dashboard:
 * `/api/fs/download` streams any size; older Hermes only serve
 * `/api/fs/read-data-url` (up to 16 MB).
 */
object HostFiles {
    suspend fun read(hermes: HermesConnection, path: String, profile: String = hermes.profile.value): HostFile {
        val name = path.substringAfterLast('/').ifEmpty { "file" }
        val query = mapOf("path" to path, "profile" to profile.takeIf { it != "default" })
        return try {
            val (bytes, type) = hermes.rest { it.getBytes("api/fs/download", query) }
            HostFile(name, bytes, type?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" } ?: guessMime(name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DashboardHttpException && e.code != 404 && e.code != 405) throw e
            val result = hermes.rest { it.getJson("api/fs/read-data-url", query) }
            val (bytes, mime) = result["dataUrl"].str?.let(WorkspaceParser::decodeDataUrl)
                ?: throw IllegalStateException("The gateway returned an invalid file payload.")
            HostFile(name, bytes, mime ?: guessMime(name))
        }
    }

    fun guessMime(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** Writes the file under the shared cache folder and returns its content URI. */
    suspend fun stage(context: Context, file: HostFile): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared/${System.nanoTime()}").apply { mkdirs() }
        val safeName = file.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val out = File(dir, safeName)
        out.writeBytes(file.bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.files", out)
    }

    /** Opens the file in another app, or the share sheet when nothing can view it. */
    fun open(context: Context, uri: Uri, mimeType: String?) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            share(context, uri, mimeType)
        }
    }

    fun share(context: Context, uri: Uri, mimeType: String?) {
        val send = Intent(Intent.ACTION_SEND).setType(mimeType ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }

    /** Decodes an image no larger than about `maxPixels` on its long side. */
    fun decodeBitmap(bytes: ByteArray, maxPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
