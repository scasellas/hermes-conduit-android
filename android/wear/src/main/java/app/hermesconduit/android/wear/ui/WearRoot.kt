package app.hermesconduit.android.wear.ui

import android.Manifest
import android.app.Activity
import android.app.RemoteInput
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import app.hermesconduit.android.wear.L10n
import app.hermesconduit.android.wear.SpeechInput
import app.hermesconduit.android.wear.WatchCall
import app.hermesconduit.android.wear.wearApp

private const val REPLY_KEY = "utterance"

/**
 * The watch app's screens. While a call is on (or its summary is up) the call
 * screen covers everything; otherwise home, recent chats, approvals, the call
 * log and settings, with swipe-to-go-back.
 */
@Composable
fun WearRoot(talkRequests: Int, talkSource: String) {
    val context = LocalContext.current
    val app = context.wearApp
    val call by app.call.state.collectAsStateWithLifecycle()

    // Start a call: ask for the microphone first; without it the call uses
    // the system's voice input screen.
    var pendingStart by remember { mutableStateOf<Triple<String, String?, String?>?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.let { (source, chat, title) -> app.call.start(source, chat, title) }
        pendingStart = null
    }
    val startCall: (String, String?, String?) -> Unit = { source, chat, title ->
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) app.call.start(source, chat, title)
        else {
            pendingStart = Triple(source, chat, title)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(talkRequests) {
        if (talkRequests > 0 && !app.call.state.value.isActive) startCall(talkSource, null, null)
    }

    // The system voice input screen, for watches without a recognizer service.
    val keyboard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(REPLY_KEY)?.toString() }
        app.call.voiceScreenResult(text)
    }
    val voiceScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
        app.call.voiceScreenResult(text)
    }
    LaunchedEffect(call.voiceScreenRequest) {
        if (call.voiceScreenRequest == 0 || !call.isActive) return@LaunchedEffect
        val recognizer = SpeechInput.intent()
        if (recognizer.resolveActivity(context.packageManager) != null) {
            runCatching { voiceScreen.launch(recognizer) }.onFailure { launchKeyboard(keyboard::launch) }
        } else {
            launchKeyboard(keyboard::launch)
        }
    }

    MaterialTheme(colors = Colors(primary = WatchColors.aura, secondary = WatchColors.voice, error = WatchColors.attention)) {
        if (call.phase != WatchCall.Phase.IDLE) {
            CallScreen(call, app.call, onRetry = { startCall("retry", null, null) })
            return@MaterialTheme
        }
        val nav = rememberSwipeDismissableNavController()
        SwipeDismissableNavHost(nav, startDestination = "home") {
            composable("home") { HomeScreen(onTalk = { startCall("tap", null, null) }, navigate = { nav.navigate(it) }) }
            composable("chats") { ChatsScreen(onPick = { id, title -> startCall("chat", id, title) }) }
            composable("approvals") { ApprovalsScreen(onOpen = { nav.navigate("approval") }) }
            composable("approval") { ApprovalDetailScreen(onDone = { nav.popBackStack() }) }
            composable("log") { LogScreen(onClear = { nav.navigate("log.clear") }) }
            composable("log.clear") { ConfirmClearScreen(onDone = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(navigate = { nav.navigate(it) }) }
            composable("engine") { EnginePickerScreen(onDone = { nav.popBackStack() }) }
        }
    }
}

private fun launchKeyboard(launch: (android.content.Intent) -> Unit) {
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(REPLY_KEY).setLabel(L10n.tr("Talk to Hermes")).build()))
    runCatching { launch(intent) }
}
