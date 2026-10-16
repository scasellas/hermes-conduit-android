package app.hermesconduit.android.voice

import app.hermesconduit.android.core.audio.AudioRoutePort
import app.hermesconduit.android.core.audio.BargeInRoutePolicy
import app.hermesconduit.android.core.audio.HermesLiveTranscription
import app.hermesconduit.android.core.audio.HermesSpeechStream
import app.hermesconduit.android.core.audio.HermesVoiceGateway
import app.hermesconduit.android.core.audio.Pcm
import app.hermesconduit.android.core.audio.SpeakStreamFrame
import app.hermesconduit.android.core.audio.SpeakStreamFraming
import app.hermesconduit.android.core.audio.SpeechServerTranscription
import app.hermesconduit.android.core.audio.SpeechSocket
import app.hermesconduit.android.core.audio.TranscriptionTimeouts
import app.hermesconduit.android.core.audio.VoiceSocket
import app.hermesconduit.android.core.audio.VoiceSpeechDetection
import app.hermesconduit.android.core.audio.VoiceSpeechDetector
import app.hermesconduit.android.core.audio.VoiceSpeechDetectorConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceSpeechDetectorTest {
    @Test
    fun quietSpeechRiseAboveNearSilenceFloorIsAccepted() {
        val detector = VoiceSpeechDetector()
        val detections = listOf(0.003f, 0.004f, 0.003f, 0.003f, 0.018f, 0.026f, 0.034f, 0.028f, 0.004f, 0.003f).map(detector::observe)
        val n = VoiceSpeechDetection.NONE
        assertEquals(listOf(n, n, n, n, n, n, n, VoiceSpeechDetection.STARTED, n, n), detections)
    }

    @Test
    fun steadyAmbientNoiseNeverBecomesSpeech() {
        val detector = VoiceSpeechDetector()
        assertTrue((0 until 120).map { detector.observe(0.015f) }.all { it == VoiceSpeechDetection.NONE })
    }

    @Test
    fun louderRoomFloorAdaptsAndRelativeSpeechRiseIsAccepted() {
        val detector = VoiceSpeechDetector()
        repeat(40) { detector.observe(0.02f) }
        assertEquals(
            listOf(VoiceSpeechDetection.NONE, VoiceSpeechDetection.STARTED, VoiceSpeechDetection.CONTINUED),
            listOf(0.065f, 0.07f, 0.06f).map(detector::observe),
        )
    }

    @Test
    fun singleIsolatedSpikeDoesNotStartSpeech() {
        val detector = VoiceSpeechDetector()
        repeat(6) { detector.observe(0.003f) }
        assertEquals(VoiceSpeechDetection.NONE, detector.observe(0.03f))
        assertEquals(VoiceSpeechDetection.NONE, detector.observe(0.003f))
        assertEquals(VoiceSpeechDetection.NONE, detector.observe(0.03f))
    }

    @Test
    fun continuationUsesLowerHysteresisAndResetClearsState() {
        val detector = VoiceSpeechDetector()
        repeat(6) { detector.observe(0.003f) }
        val run = listOf(0.026f, 0.038f, 0.024f, 0.032f).map(detector::observe)
        assertEquals(VoiceSpeechDetection.STARTED, run.last())
        assertFalse(run.dropLast(1).contains(VoiceSpeechDetection.STARTED))
        assertEquals(VoiceSpeechDetection.CONTINUED, detector.observe(0.008f))
        assertEquals(VoiceSpeechDetection.NONE, detector.observe(0.002f))
        detector.reset()
        assertTrue(detector.noiseFloor <= 0.004f)
        repeat(4) { detector.observe(0.003f) }
        assertEquals(
            listOf(VoiceSpeechDetection.NONE, VoiceSpeechDetection.NONE, VoiceSpeechDetection.NONE, VoiceSpeechDetection.STARTED),
            listOf(0.018f, 0.026f, 0.034f, 0.028f).map(detector::observe),
        )
    }

    @Test
    fun loudSamplesAndGarbage() {
        assertEquals(VoiceSpeechDetection.STARTED, VoiceSpeechDetector().observe(0.1f))
        val detector = VoiceSpeechDetector()
        repeat(6) { detector.observe(0.003f) }
        assertEquals(VoiceSpeechDetection.NONE, detector.observe(Float.NaN))
        assertTrue(detector.noiseFloor.isFinite())
        assertEquals(VoiceSpeechDetection.NONE, detector.observe(0.026f))
        assertEquals(VoiceSpeechDetection.STARTED, detector.observe(0.5f))
    }

    @Test
    fun thresholdBoundariesAndLoudRooms() {
        val quiet = VoiceSpeechDetector()
        repeat(16) { quiet.observe(0.003f) }
        assertEquals(VoiceSpeechDetection.NONE, quiet.observe(0.012f))
        assertEquals(VoiceSpeechDetection.STARTED, quiet.observe(0.012f))
        val louder = VoiceSpeechDetector()
        repeat(6) { louder.observe(0.02f) }
        assertEquals(VoiceSpeechDetection.STARTED, louder.observe(0.075f))
        val loudRoom = VoiceSpeechDetector()
        assertTrue((0 until 40).map { loudRoom.observe(0.05f) }.all { it == VoiceSpeechDetection.NONE })
        assertEquals(0.05f, loudRoom.noiseFloor, 0.005f)
        assertTrue(listOf(0.09f, 0.12f, 0.08f, 0.11f).map(loudRoom::observe).contains(VoiceSpeechDetection.STARTED))
    }

    @Test
    fun immediateQuietSpeechWithNaturalDynamicsIsAccepted() {
        val detector = VoiceSpeechDetector()
        val detections = listOf(0.018f, 0.024f, 0.032f, 0.027f, 0.035f, 0.023f, 0.031f, 0.026f, 0.034f, 0.021f).map(detector::observe)
        assertTrue(detections.contains(VoiceSpeechDetection.STARTED))
        assertTrue(detector.noiseFloor <= VoiceSpeechDetectorConstants().minimumSpeechStartThreshold)
    }
}

class PcmAndRouteTest {
    @Test
    fun wavHeaderDescribesMonoPcm16() {
        val wav = Pcm.wav(ByteArray(320), 16_000)
        assertEquals(44 + 320, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(16_000, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8))
        assertEquals("data", String(wav, 36, 4))
    }

    @Test
    fun peakAndResampling() {
        val loud = Pcm.bytes(shortArrayOf(0, 16_384, -32_768, 100))
        assertEquals(1f, Pcm.peak(loud), 0.0001f)
        assertEquals(0.5f, Pcm.peak(Pcm.bytes(shortArrayOf(16_384, -100))), 0.001f)
        val up = Pcm.resampled(Pcm.bytes(ShortArray(1_600) { 1_000 }), 16_000, 24_000)
        assertEquals(2_400 * 2, up.size)
        assertTrue(Pcm.samples(up).all { it.toInt() == 1_000 })
        val ring = Pcm.Ring(4)
        ring.append(byteArrayOf(1, 2, 3))
        ring.append(byteArrayOf(4, 5, 6))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), ring.snapshot())
    }

    @Test
    fun transcriptionTimeoutScalesWithThePayload() {
        assertEquals(TranscriptionTimeouts.MINIMUM_MS, TranscriptionTimeouts.forDataUrl(10))
        assertEquals(400_000L, TranscriptionTimeouts.forDataUrl(4_000_000))
        assertEquals(TranscriptionTimeouts.MAXIMUM_MS, TranscriptionTimeouts.forDataUrl(100_000_000))
    }

    @Test
    fun bargeInRoutePolicyIsFailSafe() {
        assertEquals(BargeInRoutePolicy.FULL_DUPLEX, BargeInRoutePolicy.resolve(listOf(AudioRoutePort.HEADPHONES), emptyList()))
        assertEquals(BargeInRoutePolicy.SPEAKER_SAFE_HALF_DUPLEX, BargeInRoutePolicy.resolve(listOf(AudioRoutePort.BUILT_IN_SPEAKER), emptyList()))
        assertEquals(BargeInRoutePolicy.SPEAKER_SAFE_HALF_DUPLEX, BargeInRoutePolicy.resolve(listOf(AudioRoutePort.BLUETOOTH_A2DP), emptyList()))
        assertEquals(BargeInRoutePolicy.FULL_DUPLEX, BargeInRoutePolicy.resolve(listOf(AudioRoutePort.BLUETOOTH_HEADSET), listOf(AudioRoutePort.BLUETOOTH_HEADSET)))
        assertEquals(BargeInRoutePolicy.SPEAKER_SAFE_HALF_DUPLEX, BargeInRoutePolicy.resolve(listOf(AudioRoutePort.HEADPHONES, AudioRoutePort.BUILT_IN_SPEAKER), emptyList()))
        assertEquals(BargeInRoutePolicy.SPEAKER_SAFE_HALF_DUPLEX, BargeInRoutePolicy.resolve(emptyList(), emptyList()))
        assertEquals(BargeInRoutePolicy.FULL_DUPLEX, BargeInRoutePolicy.resolve(listOf(AudioRoutePort.BUILT_IN_SPEAKER), emptyList(), echoCancelledTalkOver = true))
    }

    @Test
    fun fallbackDataUrlsDecode() {
        assertArrayEquals(byteArrayOf(1, 2, 3), HermesVoiceGateway.decodeDataUrl("data:audio/mpeg;base64,AQID"))
        assertNull(HermesVoiceGateway.decodeDataUrl("https://example.com/a.mp3"))
        assertNull(HermesVoiceGateway.decodeDataUrl("data:audio/mpeg,AQID"))
    }
}

class SpeakStreamTest {
    private class FakeSocket : SpeechSocket, VoiceSocket {
        val sent = mutableListOf<Any>()
        var refuse = false
        var cancelled = false
        override fun send(text: String): Boolean = sendText(text)
        override fun sendText(text: String): Boolean {
            if (refuse) return false
            sent += text
            return true
        }
        override fun sendBytes(bytes: ByteArray): Boolean {
            if (refuse) return false
            sent += bytes
            return true
        }
        override fun cancel() { cancelled = true }
        override fun close() { cancelled = true }
    }

    @Test
    fun framingTellsControlFromPcm() {
        assertEquals(SpeakStreamFrame.Start(22_050), SpeakStreamFraming.parse("""{"type":"start","sample_rate":22050}"""))
        assertEquals(SpeakStreamFrame.Start(24_000), SpeakStreamFraming.parse("""{"type":"start"}"""))
        assertEquals(SpeakStreamFrame.End, SpeakStreamFraming.parse("""{"type":"end"}""".toByteArray()))
        assertEquals(SpeakStreamFrame.Fallback, SpeakStreamFraming.parse("""{"type":"fallback"}"""))
        assertEquals(SpeakStreamFrame.Error("boom"), SpeakStreamFraming.parse("""{"type":"error","message":"boom"}"""))
        assertEquals(SpeakStreamFrame.Ignored, SpeakStreamFraming.parse("not json"))
        assertEquals(SpeakStreamFrame.Ignored, SpeakStreamFraming.parse("""{"type":"progress"}"""))
        val pcm = SpeakStreamFraming.parse(byteArrayOf(1, 0, 2, 0))
        assertTrue(pcm is SpeakStreamFrame.Pcm)
        assertEquals("""{"text":"Hi \"you\""}""", SpeakStreamFraming.textFrame("Hi \"you\""))
        assertEquals("""{"done":true}""", SpeakStreamFraming.DONE_FRAME)
    }

    @Test
    fun streamedPcmPlaysAndEndCompletes() = runTest {
        val socket = FakeSocket()
        val started = mutableListOf<Int>()
        val chunks = mutableListOf<Int>()
        val stream = HermesSpeechStream(this, socket, { error("no fallback") }, { started += it }, { data, _ -> chunks += data.size }, {})
        stream.append("Hello")
        val result = async { stream.finish() }
        runCurrent()
        stream.onText("""{"type":"start","sample_rate":16000}""")
        stream.onBytes(ByteArray(640))
        stream.onBytes(ByteArray(320))
        stream.onText("""{"type":"end"}""")
        assertTrue(result.await())
        assertEquals(listOf(16_000), started)
        assertEquals(listOf(640, 320), chunks)
        assertEquals(listOf("""{"text":"Hello"}""", """{"done":true}"""), socket.sent)
    }

    @Test
    fun aRefusedHandshakeFallsBackToTheWholeClip() = runTest {
        val socket = FakeSocket()
        var fallbackText: String? = null
        var clip: ByteArray? = null
        val stream = HermesSpeechStream(this, socket, { text -> fallbackText = text; byteArrayOf(9, 9) }, {}, { _, _ -> }, { clip = it })
        stream.append("Hello ")
        stream.onFailure(java.io.IOException("403"))
        stream.append("world")
        assertFalse(stream.finish())
        assertEquals("Hello world", fallbackText)
        assertArrayEquals(byteArrayOf(9, 9), clip)
    }

    @Test
    fun serverFallbackAndErrorFrames() = runTest {
        val socket = FakeSocket()
        var clip: ByteArray? = null
        val stream = HermesSpeechStream(this, socket, { byteArrayOf(7) }, {}, { _, _ -> }, { clip = it })
        stream.append("Hi")
        val result = async { stream.finish() }
        runCurrent()
        stream.onText("""{"type":"fallback"}""")
        assertFalse(result.await())
        assertTrue(socket.cancelled)
        assertArrayEquals(byteArrayOf(7), clip)

        val failing = HermesSpeechStream(this, FakeSocket(), { byteArrayOf() }, {}, { _, _ -> }, {})
        failing.append("Hi")
        val failed = async { runCatching { failing.finish() } }
        runCurrent()
        failing.onText("""{"type":"error","message":"quota exceeded, url='https://x.example/?key=1'"}""")
        val error = failed.await().exceptionOrNull()
        assertEquals("quota exceeded", error?.message)
    }

    @Test
    fun silenceAfterFinishFallsBackOrTimesOut() = runTest {
        var clip: ByteArray? = null
        val quiet = HermesSpeechStream(this, FakeSocket(), { byteArrayOf(5) }, {}, { _, _ -> }, { clip = it }, finishIdleTimeoutMs = 1_000, now = { testScheduler.currentTime })
        quiet.append("Hi")
        val result = async { quiet.finish() }
        advanceTimeBy(1_500)
        assertFalse(result.await())
        assertArrayEquals(byteArrayOf(5), clip)

        val stalled = HermesSpeechStream(this, FakeSocket(), { byteArrayOf() }, {}, { _, _ -> }, {}, finishIdleTimeoutMs = 1_000, now = { testScheduler.currentTime })
        stalled.append("Hi")
        stalled.onBytes(ByteArray(64))
        val outcome = async { runCatching { stalled.finish() } }
        advanceTimeBy(1_500)
        assertEquals("Hermes speech streaming timed out.", outcome.await().exceptionOrNull()?.message)
    }

    @Test
    fun cancelEndsFinishWithCancellation() = runTest {
        val socket = FakeSocket()
        val stream = HermesSpeechStream(this, socket, { byteArrayOf() }, {}, { _, _ -> }, {})
        stream.append("Hi")
        val result = async { runCatching { stream.finish() } }
        runCurrent()
        stream.cancel()
        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertTrue(socket.cancelled)
        try {
            stream.append("more")
            fail("a cancelled stream takes no more text")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun liveTranscriptionSendsRateAudioAndEos() = runTest {
        val socket = FakeSocket()
        val partials = mutableListOf<String>()
        var unavailable = false
        val live = HermesLiveTranscription(this, socket, 16_000, { partials += it }, { unavailable = true })
        live.push(ByteArray(4))
        live.onText("""{"type":"partial","text":" hello "}""")
        val result = async { live.finish() }
        runCurrent()
        live.onText("""{"type":"final","transcript":"hello world"}""")
        assertEquals("hello world", result.await())
        assertEquals(listOf("hello"), partials)
        assertEquals("""{"sample_rate":16000}""", socket.sent.first())
        assertEquals("""{"eos":true}""", socket.sent.last())
        assertFalse(unavailable)

        var refused = false
        val noLive = HermesLiveTranscription(this, FakeSocket(), 16_000, {}, { refused = true })
        noLive.onText("""{"type":"error","message":"no streaming"}""")
        assertTrue("an error before any words means this profile has no live STT", refused)
        assertNull(noLive.finish())
    }

    @Test
    fun speechServerJoinsFinishedSegments() = runTest {
        val socket = FakeSocket()
        val partials = mutableListOf<String>()
        val server = SpeechServerTranscription(this, socket, 16_000, { partials += it })
        assertTrue((socket.sent.first() as String).contains("\"create_response\":false"))
        server.push(ByteArray(3_200))
        assertTrue((socket.sent[1] as String).contains("input_audio_buffer.append"))
        server.onText("""{"type":"input_audio_buffer.speech_started"}""")
        server.onText("""{"type":"conversation.item.input_audio_transcription.delta","delta":"turn the"}""")
        server.onText("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"Turn the lights on."}""")
        val result = async { server.finish() }
        runCurrent()
        assertEquals("Turn the lights on.", result.await())
        assertEquals(listOf("turn the", "Turn the lights on."), partials)
        assertTrue(socket.sent.any { it is String && it.contains("input_audio_buffer.commit") })
    }
}
