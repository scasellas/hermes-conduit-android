package app.hermesconduit.android.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ComposerDictation
import app.hermesconduit.android.model.DictationCustomWords
import app.hermesconduit.android.model.DictationEngine
import app.hermesconduit.android.model.HermesSessionReference
import app.hermesconduit.android.model.VoiceLevelMeterMath
import app.hermesconduit.android.ui.LocalNavigate
import app.hermesconduit.android.ui.nav.activityAppViewModel
import kotlin.math.PI
import kotlin.math.sin

/** The speaker button under an assistant reply: read aloud, stop, or resume a paused reply. */
@Composable
fun ReadAloudButton(message: ChatMessage) {
    val context = LocalContext.current
    val hub = remember { VoiceHub.get(context) }
    val state by hub.readAloud.state.collectAsStateWithLifecycle()
    val paused by hub.readAloud.isPaused.collectAsStateWithLifecycle()
    val capability by hub.capability.collectAsStateWithLifecycle()
    val appVm = activityAppViewModel()
    val active = hub.readAloud.isActiveMessage(message.id)
    val isPaused = active && paused
    val preparing = (state as? ReadAloudController.State.Preparing)?.messageId == message.id
    @Suppress("UNUSED_VARIABLE") val tick = capability
    val unavailable = hub.readAloudUnavailableReason()
    IconButton(
        onClick = {
            if (isPaused) {
                hub.readAloud.resume()
                return@IconButton
            }
            if (!active && unavailable != null) {
                Toast.makeText(context, unavailable, Toast.LENGTH_LONG).show()
                return@IconButton
            }
            appVm?.sessions?.value?.sessions?.let { list -> HermesSessionReference.titles = list.associate { it.id to it.title } }
            app.hermesconduit.android.core.Haptics.light()
            hub.readAloud.toggle(message.id, message.content)
        },
        modifier = Modifier.size(36.dp).alpha(if (unavailable != null && !active) 0.45f else 1f),
    ) {
        when {
            preparing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> Icon(
                when {
                    isPaused -> Icons.Default.PlayArrow
                    active -> Icons.Default.Stop
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                when {
                    isPaused -> t("Resume reading response")
                    active -> t("Stop reading response")
                    else -> t("Read response aloud")
                },
                Modifier.size(18.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A compact "the microphone hears me" meter: five staircase bars on a dBFS scale. */
@Composable
fun VoiceInputLevelMeter(level: Float, isActive: Boolean, modifier: Modifier = Modifier) {
    val target = if (isActive) VoiceLevelMeterMath.displayFraction(level).toFloat() else 0f
    val fraction by animateFloatAsState(target, tween(120), label = "meter")
    val description = when {
        !isActive || target <= 0f -> t("Silent")
        target < 0.3f -> t("Low")
        target < 0.65f -> t("Medium")
        else -> t("High")
    }
    val label = t("Microphone input level")
    Row(
        modifier.semantics { contentDescription = "$label: $description" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(5) { index ->
            val lit = fraction >= (index + 1) / 6f
            Box(
                Modifier
                    .width(5.dp)
                    .height((6 + index * 3).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (lit) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
            )
        }
    }
}

/**
 * The composer's dictate button: the chosen engine writes what is said into
 * the draft after what was there, replacing only what this dictation wrote.
 * Typing over it ends the dictation, keeping its words.
 */
@Composable
fun DictationButton(text: String, onTextChange: (String) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { ComposerDictationService(context, scope) }
    val capturing by service.isCapturing.collectAsStateWithLifecycle()
    val starting by service.isStarting.collectAsStateWithLifecycle()
    val transcribing by service.isTranscribing.collectAsStateWithLifecycle()
    val level by service.level.collectAsStateWithLifecycle()
    var prefix by remember { mutableStateOf("") }
    var lastWrite by remember { mutableStateOf<String?>(null) }
    val currentText by androidx.compose.runtime.rememberUpdatedState(text)
    DisposableEffect(Unit) { onDispose { service.cancel() } }
    // Typing (or a replaced draft) ends a dictation, keeping its words.
    LaunchedEffect(text) {
        if (service.isDictating && !ComposerDictation.draftIsAsDictationLeftIt(text, prefix, lastWrite)) service.yieldToTyping()
    }

    fun begin() {
        val engine = DictationEngine.resolved(context.appSettings.getString(AppSettings.DICTATION_ENGINE, DictationEngine.DEVICE.raw))
        val words = DictationCustomWords.decode(context.appSettings.getString(AppSettings.DICTATION_CUSTOM_WORDS, ""))
        prefix = currentText
        lastWrite = null
        service.onTranscript = { dictated ->
            if (ComposerDictation.draftIsAsDictationLeftIt(currentText, prefix, lastWrite)) {
                val draft = ComposerDictation.draft(prefix, dictated)
                lastWrite = draft
                onTextChange(draft)
            } else if (!service.isDictating || transcribingNow(service)) {
                // Words still on their way after the person typed land at the draft's end.
                val draft = ComposerDictation.draft(currentText, dictated)
                lastWrite = draft
                onTextChange(draft)
            }
        }
        service.onFailure = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        service.onFinish = { lastWrite = null }
        try {
            service.start(engine, words) { VoiceHub.get(context).gateway() }
            app.hermesconduit.android.core.Haptics.light()
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: L10n.tr("Dictation isn't available right now."), Toast.LENGTH_LONG).show()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else Toast.makeText(context, L10n.tr("Dictation needs microphone access. You can allow it in Settings."), Toast.LENGTH_LONG).show()
    }

    IconButton(
        onClick = {
            when (ComposerDictation.tap(capturing, starting, enabled, transcribing)) {
                ComposerDictation.Tap.STOP -> service.stop()
                ComposerDictation.Tap.CANCEL_START -> service.cancel()
                ComposerDictation.Tap.NOTHING -> Unit
                ComposerDictation.Tap.START -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        modifier = Modifier.size(52.dp),
    ) {
        when {
            transcribing || starting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            capturing -> Box(contentAlignment = Alignment.Center) {
                val fraction = VoiceLevelMeterMath.displayFraction(level).toFloat()
                Box(
                    Modifier
                        .size((28 + 16 * fraction).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                )
                Icon(Icons.Default.Stop, t("Stop dictation"), tint = MaterialTheme.colorScheme.primary)
            }
            else -> Icon(Icons.Default.Mic, t("Dictate"))
        }
    }
}

private fun transcribingNow(service: ComposerDictationService) = service.isTranscribing.value

/** Starts a voice conversation from the composer. */
@Composable
fun VoiceConversationButton(enabled: Boolean = true) {
    val navigate = LocalNavigate.current
    FilledIconButton(onClick = { navigate("voice") }, enabled = enabled, modifier = Modifier.size(52.dp)) {
        Icon(Icons.Default.GraphicEq, t("Voice conversation"))
    }
}

/**
 * The call orb: a soft gradient sphere that breathes with the conversation
 * (the iOS LiquidOrb, without its Metal shader). Holds still when the
 * "Animate the call orb" setting is off.
 */
@Composable
fun VoiceOrb(state: VoiceConversationState, level: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate by context.appSettings.bool(AppSettings.VOICE_ANIMATE_ORB, true).collectAsStateWithLifecycle()
    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (state == VoiceConversationState.SPEAKING) 1_400 else 3_200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val meter by animateFloatAsState(VoiceLevelMeterMath.displayFraction(level).toFloat(), tween(120), label = "level")
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val tint = when (state) {
        VoiceConversationState.LISTENING -> primary
        VoiceConversationState.SPEAKING -> tertiary
        VoiceConversationState.THINKING, VoiceConversationState.TRANSCRIBING -> secondary
        VoiceConversationState.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Canvas(modifier) {
        val t = if (animate) phase else 0f
        val wave = sin(t * 2 * PI).toFloat()
        val energy = when (state) {
            VoiceConversationState.LISTENING -> meter
            VoiceConversationState.SPEAKING -> 0.55f + 0.25f * wave
            VoiceConversationState.THINKING, VoiceConversationState.TRANSCRIBING -> 0.25f + 0.1f * wave
            else -> 0.1f
        }
        val base = size.minDimension / 2f
        val radius = base * (0.62f + 0.18f * (if (animate) energy else 0.2f))
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.28f), Color.Transparent), center, base), base)
        val offset = Offset(center.x + radius * 0.12f * wave, center.y - radius * 0.12f * sin((t + 0.25f) * 2 * PI).toFloat())
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.55f), tint.copy(alpha = 0.2f)), offset, radius), radius, center)
    }
}
