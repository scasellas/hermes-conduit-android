package app.hermesconduit.android.core.audio

import java.nio.ByteBuffer

/**
 * Feeds an [ExternalMicrophone] (the car's) to an engine that reads its own
 * microphone on its own clock: WebRTC's audio thread, for GPT-Live. A reader
 * thread keeps the newest [Pcm.CAPTURE_SAMPLE_RATE] PCM16 audio in a short
 * ring; each engine buffer is overwritten with it, silence where the car
 * hasn't delivered yet, so the engine's pacing never waits on the car.
 */
class ExternalMicrophoneRelay(private val microphone: ExternalMicrophone, capacityMs: Int = 200) {
    private val ring = Pcm.Ring(Pcm.CAPTURE_SAMPLE_RATE * 2 * capacityMs / 1000)
    private val lock = Object()
    @Volatile private var running = false
    private var reader: Thread? = null

    /** The car's microphone is delivering; once it's gone (the car disconnected) the engine keeps its own audio. */
    @Volatile var isLive = false
        private set

    fun start() {
        if (running) return
        microphone.start()
        running = true
        isLive = true
        reader = Thread({
            val buffer = ByteArray(AudioRecordCapture.FRAME_BYTES)
            while (running) {
                val read = try { microphone.read(buffer, 0, buffer.size) } catch (_: Exception) { -1 }
                if (read < 0) break
                if (read > 0) write(buffer, read)
            }
            isLive = false
        }, "conduit-car-relay").apply { start() }
    }

    fun stop() {
        if (!running) return
        running = false
        isLive = false
        reader = null
        runCatching { microphone.stop() }
        synchronized(lock) { ring.clear() }
    }

    /** What the reader thread delivers (internal for tests). */
    internal fun write(bytes: ByteArray, length: Int) {
        synchronized(lock) { ring.append(bytes, length) }
    }

    /**
     * Replaces the first [length] bytes of [target] with the car's audio,
     * oldest first; what the car hasn't delivered yet is silence.
     */
    fun fill(target: ByteArray, length: Int = target.size) {
        val count = minOf(length, target.size)
        val moved = synchronized(lock) { ring.take(target, count) }
        for (i in moved until count) target[i] = 0
    }

    /** [fill] for a direct buffer, from its position 0. */
    fun fill(target: ByteBuffer, length: Int) {
        val bytes = ByteArray(minOf(length, target.capacity()))
        fill(bytes)
        val copy = target.duplicate()
        copy.clear()
        copy.put(bytes)
    }
}
