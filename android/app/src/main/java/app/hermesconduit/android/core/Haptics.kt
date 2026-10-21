package app.hermesconduit.android.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.StreamEvent

/**
 * The iOS Haptics vocabulary on Android vibration effects. Every call
 * respects Settings > Chat > Haptic feedback.
 */
object Haptics {
    private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    private val vibrator: Vibrator?
        get() {
            val context = appContext ?: return null
            return if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }

    private val enabled: Boolean get() = appContext?.appSettings?.getBool(AppSettings.HAPTICS, true) ?: false

    private fun play(predefined: Int, fallbackMs: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(predefined))
            else v.vibrate(VibrationEffect.createOneShot(fallbackMs, amplitude))
        }
    }

    fun selection() = play(VibrationEffect.EFFECT_TICK, 8)
    fun light() = play(VibrationEffect.EFFECT_TICK, 12)
    fun medium() = play(VibrationEffect.EFFECT_CLICK, 20)
    fun success() = play(VibrationEffect.EFFECT_DOUBLE_CLICK, 30)
    fun warning() = play(VibrationEffect.EFFECT_HEAVY_CLICK, 40)
    fun error() = play(VibrationEffect.EFFECT_HEAVY_CLICK, 60)
    fun toolStarted() = play(VibrationEffect.EFFECT_TICK, 8)

    /** Turn-level feedback, like ResponseHapticPolicy: one start tap on the first text, a tick per tool, a finish tap. */
    class ResponseTracker {
        private var active = false
        private var startPlayed = false
        private var lastToolMs = 0L

        fun onEvent(event: StreamEvent) {
            when (event) {
                is StreamEvent.MessageStart, is StreamEvent.ReasoningDelta -> active = true
                is StreamEvent.MessageDelta -> {
                    active = true
                    if (!startPlayed) { startPlayed = true; light() }
                }
                is StreamEvent.ToolStart -> if (event.name.lowercase() != "clarify") {
                    val now = System.currentTimeMillis()
                    if (now - lastToolMs > 400) toolStarted()
                    lastToolMs = now
                }
                is StreamEvent.Approval, is StreamEvent.Clarify, is StreamEvent.InputPromptRequest -> warning()
                is StreamEvent.Error -> { if (active) error(); reset() }
                is StreamEvent.Interrupted -> reset()
                is StreamEvent.Busy -> if (!event.busy && active) { success(); reset() }
                else -> Unit
            }
        }

        private fun reset() {
            active = false
            startPlayed = false
        }
    }
}
