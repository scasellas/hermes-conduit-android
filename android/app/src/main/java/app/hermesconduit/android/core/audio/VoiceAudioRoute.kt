package app.hermesconduit.android.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

enum class AudioRoutePort { HEADPHONES, BLUETOOTH_HEADSET, BLUETOOTH_A2DP, BUILT_IN_SPEAKER, BUILT_IN_EARPIECE, OTHER }

/** Whether the microphone may stay live while the assistant's own speech plays. */
enum class BargeInRoutePolicy {
    /** Output acoustically isolated from the phone's microphone (headset): keep listening. */
    FULL_DUPLEX,
    /** Output can feed the microphone (loudspeaker, earpiece): capture is suspended while Hermes speaks. */
    SPEAKER_SAFE_HALF_DUPLEX;

    companion object {

        /**
         * Fail-safe: every active output must be proven safe. A Bluetooth
         * output counts only when a Bluetooth headset microphone is the input.
         * With the platform echo canceller and the user's "Talk over Hermes on
         * the speaker" choice, every route behaves like a headset.
         */
        fun resolve(outputs: List<AudioRoutePort>, inputs: List<AudioRoutePort>, echoCancelledTalkOver: Boolean = false): BargeInRoutePolicy {
            if (echoCancelledTalkOver) return FULL_DUPLEX
            if (outputs.isEmpty()) return SPEAKER_SAFE_HALF_DUPLEX
            val headsetMic = inputs.contains(AudioRoutePort.BLUETOOTH_HEADSET)
            val safe = outputs.all { port ->
                when (port) {
                    AudioRoutePort.HEADPHONES -> true
                    AudioRoutePort.BLUETOOTH_HEADSET, AudioRoutePort.BLUETOOTH_A2DP -> headsetMic
                    else -> false
                }
            }
            return if (safe) FULL_DUPLEX else SPEAKER_SAFE_HALF_DUPLEX
        }

        fun port(type: Int): AudioRoutePort = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoutePort.HEADPHONES
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoutePort.BLUETOOTH_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRoutePort.BLUETOOTH_A2DP
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoutePort.BUILT_IN_SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoutePort.BUILT_IN_EARPIECE
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) AudioRoutePort.BLUETOOTH_HEADSET else AudioRoutePort.OTHER
        }
    }
}

/**
 * The conversation's audio session: communication mode (so the echo
 * canceller pairs capture with playback), routed to a headset when one is
 * connected and otherwise to the loudspeaker, like iOS `.defaultToSpeaker`.
 */
class VoiceAudioRoute(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int? = null
    private var callback: AudioDeviceCallback? = null
    var onRouteChanged: (() -> Unit)? = null

    fun begin() {
        if (previousMode != null) return
        previousMode = audioManager.mode
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        applyRoute()
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { applyRoute(); onRouteChanged?.invoke() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { applyRoute(); onRouteChanged?.invoke() }
        }
        callback = deviceCallback
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    fun end() {
        val mode = previousMode ?: return
        previousMode = null
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        callback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
        runCatching { audioManager.mode = mode }
    }

    private fun applyRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val preferred = devices.firstOrNull { BargeInRoutePolicy.port(it.type) == AudioRoutePort.BLUETOOTH_HEADSET }
                ?: devices.firstOrNull { BargeInRoutePolicy.port(it.type) == AudioRoutePort.HEADPHONES }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            preferred?.let { runCatching { audioManager.setCommunicationDevice(it) } }
        } else {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { BargeInRoutePolicy.port(it.type) }
            val headset = outputs.any { it == AudioRoutePort.HEADPHONES || it == AudioRoutePort.BLUETOOTH_HEADSET }
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = !headset }
        }
    }

    /** The live route as the barge-in policy sees it. */
    fun currentPolicy(echoCancelledTalkOver: Boolean): BargeInRoutePolicy {
        val outputs: List<AudioRoutePort>
        val inputs: List<AudioRoutePort>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.communicationDevice
            outputs = listOfNotNull(device?.let { BargeInRoutePolicy.port(it.type) })
            inputs = if (outputs.firstOrNull() == AudioRoutePort.BLUETOOTH_HEADSET) outputs else emptyList()
        } else {
            val all = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { BargeInRoutePolicy.port(it.type) }
            @Suppress("DEPRECATION")
            outputs = if (audioManager.isSpeakerphoneOn) listOf(AudioRoutePort.BUILT_IN_SPEAKER)
            else all.filter { it == AudioRoutePort.HEADPHONES || it == AudioRoutePort.BLUETOOTH_HEADSET }
                .ifEmpty { listOf(AudioRoutePort.BUILT_IN_EARPIECE) }
            @Suppress("DEPRECATION")
            inputs = if (audioManager.isBluetoothScoOn) listOf(AudioRoutePort.BLUETOOTH_HEADSET) else emptyList()
        }
        return BargeInRoutePolicy.resolve(outputs, inputs, echoCancelledTalkOver)
    }
}
