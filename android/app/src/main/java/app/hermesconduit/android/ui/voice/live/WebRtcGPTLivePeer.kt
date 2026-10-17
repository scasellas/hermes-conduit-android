package app.hermesconduit.android.ui.voice.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.audio.ExternalMicrophone
import app.hermesconduit.android.core.audio.ExternalMicrophoneRelay
import app.hermesconduit.android.core.audio.Pcm
import app.hermesconduit.android.core.live.GPTLivePeer
import app.hermesconduit.android.core.live.GPTLiveProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The WebRTC side of a GPT-Live call: one peer connection carrying the
 * microphone out and the model's voice back as audio tracks, plus the
 * `oai-events` data channel. WebRTC's own audio device module plays the
 * model and does echo cancellation (the platform's hardware canceller), so
 * there is no separate playback. Audio focus loss (a phone call, an alarm)
 * pauses the call's audio rather than ending it (#376). WebRTC calls back
 * on its own threads; everything hops to [scope].
 */
class WebRtcGPTLivePeer(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The car's microphone while Android Auto is connected: the call hears it instead of the phone's. */
    private val externalMicrophone: ExternalMicrophone? = null,
) : GPTLivePeer {
    override var onMessage: ((String) -> Unit)? = null
    override var onDisconnected: (() -> Unit)? = null
    override var onConnectionInterrupted: ((Boolean) -> Unit)? = null
    override var onAudioPaused: ((Boolean) -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var relay: ExternalMicrophoneRelay? = null
    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var channel: DataChannel? = null
    private var microphone: AudioTrack? = null
    private var isSpeakerEnabled = true
    private var isMicrophoneEnabled = true
    private var isAudioPaused = false
    private var isInterrupted = false
    @Volatile private var isClosed = false

    override suspend fun makeOffer(): String {
        initialize(context)
        val builder = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
        externalMicrophone?.let { microphone ->
            // WebRTC keeps reading the phone's microphone for its timing; each
            // buffer it read is replaced with the car's audio before it is sent,
            // until the car goes away and the phone's audio carries the call on.
            val relay = ExternalMicrophoneRelay(microphone)
            try {
                relay.start()
            } catch (e: Exception) {
                throw IOException(L10n.tr("The microphone is not available right now."))
            }
            this.relay = relay
            builder.setInputSampleRate(Pcm.CAPTURE_SAMPLE_RATE)
                .setAudioBufferCallback { buffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimeNs ->
                    if (relay.isLive && audioFormat == AudioFormat.ENCODING_PCM_16BIT && channelCount == 1 && sampleRate == Pcm.CAPTURE_SAMPLE_RATE) {
                        relay.fill(buffer, bytesRead)
                    }
                    captureTimeNs
                }
        }
        val module = builder.createAudioDeviceModule()
        audioModule = module
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        this.factory = factory
        requestFocus()
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val connection = factory.createPeerConnection(configuration, observer) ?: throw IOException(L10n.tr("Couldn't prepare the GPT-Live call."))
        this.connection = connection
        val audioConstraints = MediaConstraints().apply {
            optional += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            optional += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            optional += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
        }
        val source = factory.createAudioSource(audioConstraints)
        val track = factory.createAudioTrack("conduit-microphone", source)
        connection.addTrack(track, listOf("conduit"))
        microphone = track
        applyMicrophoneEnabled()
        // Created before the offer so its m-line is negotiated.
        val channel = connection.createDataChannel(GPTLiveProtocol.DATA_CHANNEL_LABEL, DataChannel.Init())
            ?: throw IOException(L10n.tr("Couldn't prepare the GPT-Live call."))
        channel.registerObserver(channelObserver(channel))
        this.channel = channel

        val offerConstraints = MediaConstraints().apply { mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true") }
        val offer = suspendCancellableCoroutine { continuation ->
            connection.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description != null) continuation.resume(description) else continuation.resumeWithException(IOException(L10n.tr("Couldn't prepare the GPT-Live call.")))
                }
                override fun onCreateFailure(error: String?) = continuation.resumeWithException(IOException(L10n.tr("Couldn't prepare the GPT-Live call.")))
            }, offerConstraints)
        }
        suspendCancellableCoroutine { continuation ->
            connection.setLocalDescription(object : SdpAdapter() {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String?) = continuation.resumeWithException(IOException(L10n.tr("Couldn't prepare the GPT-Live call.")))
            }, offer)
        }
        // The offer goes out with the candidates it has after the timeout (host candidates come almost at once).
        var waited = 0L
        while (connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE && waited < ICE_GATHERING_TIMEOUT_MS && !isClosed) {
            delay(50)
            waited += 50
        }
        val sdp = connection.localDescription?.description
        if (isClosed || sdp.isNullOrEmpty()) throw IOException(L10n.tr("Couldn't prepare the GPT-Live call."))
        return sdp
    }

    override suspend fun acceptAnswer(sdp: String) {
        val connection = connection ?: throw IOException(L10n.tr("GPT-Live's call answer couldn't be used."))
        if (isClosed) throw IOException(L10n.tr("GPT-Live's call answer couldn't be used."))
        suspendCancellableCoroutine { continuation ->
            connection.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String?) {
                    runCatching { Log.w(TAG, "GPT-Live answer rejected: $error") }
                    continuation.resumeWithException(IOException(L10n.tr("GPT-Live's call answer couldn't be used.")))
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }
        // The model's track exists once the answer is in: a speaker muted while connecting stays muted.
        applySpeakerEnabled()
    }

    override fun send(text: String): Boolean {
        val channel = channel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        isMicrophoneEnabled = enabled
        applyMicrophoneEnabled()
    }

    private fun applyMicrophoneEnabled() {
        runCatching { microphone?.setEnabled(isMicrophoneEnabled && !isAudioPaused) }
    }

    override fun setSpeakerEnabled(enabled: Boolean) {
        isSpeakerEnabled = enabled
        applySpeakerEnabled()
    }

    private fun applySpeakerEnabled() {
        val connection = connection ?: return
        if (isClosed) return
        runCatching { connection.receivers.forEach { (it.track() as? AudioTrack)?.setEnabled(isSpeakerEnabled && !isAudioPaused) } }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        abandonFocus()
        runCatching { channel?.unregisterObserver(); channel?.close(); channel?.dispose() }
        channel = null
        runCatching { microphone?.setEnabled(false) }
        microphone = null
        runCatching { connection?.dispose() }
        connection = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { audioModule?.release() }
        audioModule = null
        relay?.stop()
        relay = null
    }

    // region Audio focus (#376)

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> scope.launch { focusChanged(change) } }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioManager.abandonAudioFocusRequest(request)
    }

    private fun focusChanged(change: Int) {
        if (isClosed) return
        val paused = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS
        val resumed = change == AudioManager.AUDIOFOCUS_GAIN
        if (paused && !isAudioPaused) {
            isAudioPaused = true
        } else if (resumed && isAudioPaused) {
            isAudioPaused = false
        } else return
        applyMicrophoneEnabled()
        applySpeakerEnabled()
        onAudioPaused?.invoke(isAudioPaused)
    }

    // endregion

    private fun connectionStateChanged(state: PeerConnection.PeerConnectionState) {
        if (isClosed) return
        when (state) {
            PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> onDisconnected?.invoke()
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                // WebRTC's temporary state: it usually returns to connected.
                if (isInterrupted) return
                isInterrupted = true
                onConnectionInterrupted?.invoke(true)
            }
            PeerConnection.PeerConnectionState.CONNECTED -> {
                if (!isInterrupted) return
                isInterrupted = false
                onConnectionInterrupted?.invoke(false)
            }
            else -> Unit
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            scope.launch { applySpeakerEnabled() }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            newState ?: return
            scope.launch { connectionStateChanged(newState) }
        }
    }

    private fun channelObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {
            val state = channel.state()
            scope.launch { if (!isClosed && state == DataChannel.State.CLOSED) onDisconnected?.invoke() }
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            scope.launch { if (!isClosed) onMessage?.invoke(text) }
        }
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val TAG = "GPTLive"
        const val ICE_GATHERING_TIMEOUT_MS = 3_000L
        @Volatile private var initialized = false

        private fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                initialized = true
            }
        }
    }
}
