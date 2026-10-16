package app.hermesconduit.android.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.ContentSegment
import app.hermesconduit.android.model.ContentSegmenter
import app.hermesconduit.android.model.str
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An assistant reply: Markdown with highlighted code, plus diagrams, formulas, media and previews. */
@Composable
fun RichMarkdown(content: String, fontScale: Float = 1f, streaming: Boolean = false) {
    val segments = remember(content, streaming) { ContentSegmenter.segments(content, streaming) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is ContentSegment.Markdown -> MarkdownText(segment.text, fontScale)
                is ContentSegment.Mermaid -> MarkupBlock(MarkupKind.MERMAID, segment.source, segment.complete)
                is ContentSegment.Math -> MarkupBlock(MarkupKind.MATH, segment.source, segment.complete)
                is ContentSegment.Media -> MediaBlock(segment.path, segment.alt)
                is ContentSegment.Preview -> HtmlPreviewBlock(segment.file)
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String, fontScale: Float) {
    val dark = isSystemInDarkTheme()
    val highlights = remember(dark) { Highlights.Builder().theme(SyntaxThemes.atom(darkMode = dark)) }
    // Parsed during composition: the async default flashes empty on every streamed delta.
    val state = rememberMarkdownState(text, immediate = true)
    val base = MaterialTheme.typography
    val typography = if (fontScale == 1f) markdownTypography() else markdownTypography(
        paragraph = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * fontScale),
        text = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * fontScale),
        ordered = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * fontScale),
        bullet = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * fontScale),
        list = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * fontScale),
    )
    SelectionContainer {
        Markdown(
            markdownState = state,
            typography = typography,
            components = markdownComponents(
                codeBlock = { MarkdownHighlightedCodeBlock(it.content, it.node, it.typography.code, highlights) },
                codeFence = { MarkdownHighlightedCodeFence(it.content, it.node, it.typography.code, highlights) },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

enum class MarkupKind { MERMAID, MATH }

/** Mermaid or KaTeX drawn in a small web page (renderers from jsDelivr, as on iOS); tap for full screen. */
@Composable
fun MarkupBlock(kind: MarkupKind, source: String, complete: Boolean) {
    if (!complete) {
        SourceBox(source)
        return
    }
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    var fullScreen by remember { mutableStateOf(false) }
    val html = remember(kind, source, dark) {
        markupHtml(kind, source, dark, colors.onSurface.toArgb(), colors.primary.toArgb())
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLow),
    ) {
        MarkupWebView(html, Modifier.fillMaxWidth(), minHeight = if (kind == MarkupKind.MERMAID) 120 else 40)
        Icon(
            Icons.Default.OpenInFull,
            t("Full Screen"),
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).clickable { fullScreen = true },
            tint = colors.onSurfaceVariant,
        )
    }
    if (fullScreen) {
        Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = colors.background) {
                MarkupWebView(html, Modifier.fillMaxSize(), minHeight = 0, zoomable = true)
            }
        }
    }
}

@Composable
private fun SourceBox(source: String) {
    Text(
        source,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(8.dp),
    )
}

private fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

private fun jsonString(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

fun markupHtml(kind: MarkupKind, source: String, dark: Boolean, foreground: Int, primary: Int): String {
    val bg = if (dark) "#111113" else "#ffffff"
    val fg = hex(foreground)
    val head = """<meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:$bg;color:$fg;font-family:sans-serif}#content{padding:8px;box-sizing:border-box}</style>"""
    val report = """function report(){Conduit.height(Math.ceil(document.getElementById('content').getBoundingClientRect().height));}"""
    return when (kind) {
        MarkupKind.MERMAID -> """<!doctype html><html><head>$head</head><body><div id="content"></div>
<script src="https://cdn.jsdelivr.net/npm/mermaid@11.16.0/dist/mermaid.min.js"></script>
<script>$report(async function(){const c=document.getElementById('content');if(typeof mermaid==='undefined'){c.textContent=${jsonString(source)};report();return;}try{mermaid.initialize({startOnLoad:false,securityLevel:'strict',suppressErrorRendering:true,theme:'base',themeVariables:{background:'$bg',primaryColor:'${hex(primary)}',primaryTextColor:'$fg',lineColor:'$fg'}});const r=await mermaid.render('d',${jsonString(source)});c.innerHTML=r.svg;new ResizeObserver(report).observe(c);report();}catch(e){c.textContent=${jsonString(source)};report();}})();</script></body></html>"""
        MarkupKind.MATH -> """<!doctype html><html><head>$head<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.css"></head><body><div id="content"></div>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.js"></script>
<script>$report(function(){const c=document.getElementById('content');if(typeof katex==='undefined'){c.textContent=${jsonString(source)};report();return;}try{katex.render(${jsonString(source)},c,{displayMode:true,throwOnError:false,trust:false});}catch(e){c.textContent=${jsonString(source)};}new ResizeObserver(report).observe(c);document.fonts.ready.then(report);report();})();</script></body></html>"""
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkupWebView(html: String, modifier: Modifier, minHeight: Int, zoomable: Boolean = false) {
    var height by remember { mutableIntStateOf(minHeight) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.builtInZoomControls = zoomable
                settings.displayZoomControls = false
                setBackgroundColor(0)
                webViewClient = object : WebViewClient() {
                    // Only the renderer CDN loads; links never navigate inside the block.
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun height(value: Int) {
                        post { if (value > 0) height = value }
                    }
                }, "Conduit")
                loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
            }
        },
        update = { },
        modifier = if (minHeight == 0) modifier else modifier.height(maxOf(height, minHeight).dp),
    )
}

/** Loads `/api/fs/read-data-url?path=` for a `MEDIA:` image, or offers the file. */
@Composable
fun MediaBlock(path: String, alt: String?) {
    val context = LocalContext.current
    val name = alt ?: path.substringAfterLast('/').substringAfterLast('\\')
    val ext = path.substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (isImage) {
        LaunchedEffect(path) {
            image = runCatching { loadDataUrlImage(context, path) }.getOrNull()
            failed = image == null
        }
    }
    val bitmap = image
    when {
        bitmap != null -> Image(
            bitmap,
            name,
            Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)).clickable { fullScreen = true },
            contentScale = ContentScale.FillWidth,
        )
        isImage && !failed -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy) {
                busy = true
                scope.launch {
                    openGatewayFile(context, path)
                    busy = false
                }
            },
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(ext.uppercase().ifEmpty { t("File") }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
    if (fullScreen && bitmap != null) {
        Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { fullScreen = false }, contentAlignment = Alignment.Center) {
                Image(bitmap, name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
        }
    }
}

private fun profileQuery(context: Context) = context.hermes.profile.value.takeIf { it != "default" }

suspend fun loadDataUrlImage(context: Context, path: String): ImageBitmap? {
    val result = context.hermes.rest { it.getJson("api/fs/read-data-url", mapOf("path" to path, "profile" to profileQuery(context))) }
    val dataUrl = result["dataUrl"].str ?: result["data_url"].str ?: return null
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isEmpty()) return null
    return withContext(Dispatchers.Default) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
}

/** Downloads a host file (`/api/fs/download`) and opens it with another app. */
suspend fun openGatewayFile(context: Context, path: String) {
    runCatching {
        val (bytes, type) = context.hermes.rest { it.getBytes("api/fs/download", mapOf("path" to path, "profile" to profileQuery(context))) }
        val name = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "file" }
        val file = withContext(Dispatchers.IO) {
            File(File(context.cacheDir, "shared").apply { mkdirs() }, name).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = type?.substringBefore(';')?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: app.hermesconduit.android.model.Attachment.guessMime(name)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(context, it.message ?: "Couldn't open the file.", android.widget.Toast.LENGTH_LONG).show()
    }
}

/** `::preview{file="page.html"}`: the host-written page drawn inline; other files get a card. */
@Composable
fun HtmlPreviewBlock(file: String) {
    if (!ContentSegmenter.isHtml(file)) {
        MediaBlock(file, null)
        return
    }
    val context = LocalContext.current
    var html by remember(file) { mutableStateOf<String?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    LaunchedEffect(file) {
        html = runCatching {
            context.hermes.rest { it.getJson("api/fs/read-text", mapOf("path" to file, "profile" to profileQuery(context))) }["text"].str
        }.getOrNull()
    }
    val page = html
    if (page == null) {
        MediaBlock(file, null)
        return
    }
    Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp))) {
        PreviewWebView(page, Modifier.fillMaxSize())
        Icon(
            Icons.Default.OpenInFull,
            t("Full Screen"),
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clickable { fullScreen = true },
        )
    }
    if (fullScreen) {
        Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            PreviewWebView(page, Modifier.fillMaxSize())
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWebView(html: String, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        // External links leave the preview for the browser.
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
    )
}
