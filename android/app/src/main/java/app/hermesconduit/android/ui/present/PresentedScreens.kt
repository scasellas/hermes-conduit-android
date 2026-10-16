package app.hermesconduit.android.ui.present

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.BuildConfig
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.markdown.RichMarkdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class PresentedConfirm(
    val id: String,
    val title: String,
    val preview: String,
    val actionLabel: String,
    val spokenYes: List<String>,
    val sessionId: String?,
    val expiresAtMs: Long?,
    val mapsTo: String?,
    val mapsMode: String,
) {
    fun isExpired(now: Long = System.currentTimeMillis()) = expiresAtMs?.let { it <= now } ?: false

    companion object {
        fun from(json: JsonElement): PresentedConfirm? {
            val o = json.obj ?: return null
            val id = o["id"].str?.takeIf { it.isNotEmpty() } ?: return null
            val title = o["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val maps = o["maps"].obj
            val mode = maps?.get("mode").str?.lowercase() ?: "driving"
            return PresentedConfirm(
                id = id,
                title = title.take(120),
                preview = o["preview"].str.orEmpty().take(4_000),
                actionLabel = o["action_label"].str?.trim()?.takeIf { it.isNotEmpty() }?.take(32) ?: app.hermesconduit.android.core.L10n.tr("Confirm"),
                spokenYes = o["spoken_yes"].arr?.mapNotNull { it.str?.trim()?.takeIf(String::isNotEmpty)?.take(40) }?.take(5).orEmpty(),
                sessionId = o["session_id"].str?.takeIf { it.isNotEmpty() },
                expiresAtMs = o["expires_at"].double?.let { (it * 1000).toLong() },
                mapsTo = maps?.get("to").str?.trim()?.takeIf { it.isNotEmpty() },
                mapsMode = if (mode in setOf("driving", "walking", "transit", "cycling")) mode else "driving",
            )
        }
    }
}

/**
 * Hermes' `conduit_present` tool: a confirm panel the phone shows; the answer
 * goes back on the next `present/exchange` (installation id, device, results).
 */
object PresentedScreens {
    private const val PATH = "api/plugins/conduit_push/present/exchange"
    private val features = listOf("maps-navigate")
    private val _prompts = MutableStateFlow<List<PresentedConfirm>>(emptyList())
    val prompts: StateFlow<List<PresentedConfirm>> = _prompts
    private val pendingResults = mutableListOf<Triple<String, String, String>>()
    private val mutex = Mutex()

    fun installationId(context: Context): String {
        val settings = context.appSettings
        return settings.getString("installation_id", "").ifEmpty { UUID.randomUUID().toString().also { settings.set("installation_id", it) } }
    }

    fun sync(context: Context) {
        val hermes = context.hermes
        if (!hermes.isSignedIn) return
        hermes.scope.launch {
            mutex.withLock {
                val answers = synchronized(pendingResults) { pendingResults.toList() }
                val body = buildJsonObject {
                    put("installation_id", installationId(context))
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("app_version", BuildConfig.VERSION_NAME)
                    put("results", buildJsonArray { answers.forEach { (id, outcome, via) -> add(buildJsonObject { put("id", id); put("outcome", outcome); put("via", via) }) } })
                    put("features", buildJsonArray { features.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                }
                val profile = hermes.profile.value.takeIf { it != "default" }
                val response = runCatching { hermes.rest { it.sendJson("POST", PATH, body, mapOf("profile" to profile)) } }.getOrNull() ?: return@withLock
                if (response["ok"].bool != true) return@withLock
                val sent = answers.map { it.first }.toSet()
                synchronized(pendingResults) { pendingResults.removeAll { it.first in sent } }
                _prompts.value = response["prompts"].arr?.mapNotNull(PresentedConfirm::from)?.filter { it.id !in sent && !it.isExpired() }.orEmpty()
            }
        }
    }

    /** outcome: confirmed | declined | edit; via: tap | voice | auto. */
    fun answer(context: Context, prompt: PresentedConfirm, outcome: String, via: String = "tap") {
        synchronized(pendingResults) { pendingResults += Triple(prompt.id, outcome, via) }
        _prompts.value = _prompts.value.filterNot { it.id == prompt.id }
        sync(context)
        if (outcome == "confirmed" && prompt.mapsTo != null) {
            val mode = when (prompt.mapsMode) { "walking" -> "w"; "cycling" -> "b"; "transit" -> "r"; else -> "d" }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(prompt.mapsTo)}&mode=$mode")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(prompt.mapsTo)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

@Composable
fun PresentedConfirmHost() {
    val context = LocalContext.current
    val prompts by PresentedScreens.prompts.collectAsStateWithLifecycle()
    val prompt = prompts.firstOrNull { !it.isExpired() } ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(prompt.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (prompt.preview.isNotBlank()) RichMarkdown(prompt.preview)
            }
        },
        confirmButton = { TextButton(onClick = { PresentedScreens.answer(context, prompt, "confirmed") }) { Text(prompt.actionLabel) } },
        dismissButton = {
            Column {
                TextButton(onClick = { PresentedScreens.answer(context, prompt, "edit") }) { Text(t("Change it")) }
                TextButton(onClick = { PresentedScreens.answer(context, prompt, "declined") }) { Text(t("Not now")) }
            }
        },
    )
}
