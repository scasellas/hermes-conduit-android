package app.hermesconduit.android.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import androidx.core.content.FileProvider
import app.hermesconduit.android.MainActivity
import java.io.File

/**
 * Conduit as the device's digital assistant: long-press Home/Power hands
 * Conduit a screenshot of what's on screen, which opens in a new chat ready
 * for a question — Android's form of iOS "Ask Hermes About Screen".
 */
class ConduitAssistService : VoiceInteractionService()

class ConduitAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ConduitAssistSession(this)
}

class ConduitAssistSession(context: Context) : VoiceInteractionSession(context) {
    private var screenshot: Bitmap? = null
    private var launched = false

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        this.screenshot = screenshot
        launch()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Without the screenshot permission no bitmap arrives; open a plain new chat shortly after.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ launch() }, 1_200)
    }

    private fun launch() {
        if (launched) return
        launched = true
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val shot = screenshot
        if (shot != null) {
            val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "screen-${System.currentTimeMillis()}.png")
            runCatching { file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            intent.setAction(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(EXTRA_SCREEN_QUESTION, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse("conduit://new"))
        }
        context.startActivity(intent)
        hide()
    }

    companion object {
        const val EXTRA_SCREEN_QUESTION = "screen_question"
    }
}

/** Required by the voice-interaction declaration; Conduit's dictation uses its own engines. */
class ConduitRecognitionService : android.speech.RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
