package app.hermesconduit.android.core.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** PCM16 helpers shared by every voice surface. Pure, JVM-testable. */
object Pcm {
    const val CAPTURE_SAMPLE_RATE = 16_000
    const val DEFAULT_SPEECH_SAMPLE_RATE = 24_000

    /** A 44-byte RIFF header plus the samples (mono PCM16). */
    fun wav(pcm16: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val bytesPerFrame = 2 * channels
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm16.size)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * bytesPerFrame)
        header.putShort(bytesPerFrame.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm16.size)
        return header.array() + pcm16
    }

    /** The peak of little-endian PCM16 samples, 0…1. */
    fun peak(pcm16: ByteArray, length: Int = pcm16.size): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm16[i].toInt() and 0xFF) or (pcm16[i + 1].toInt() shl 8)
            val magnitude = abs(sample.toShort().toInt())
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return (peak / 32_768f).coerceAtMost(1f)
    }

    fun samples(pcm16: ByteArray): ShortArray {
        val out = ShortArray(pcm16.size / 2)
        ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    fun bytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    /** Linear resampling between rates (16 kHz capture → 24 kHz speech server input). */
    fun resampled(pcm16: ByteArray, from: Int, to: Int): ByteArray {
        if (from == to) return pcm16
        if (from <= 0 || to <= 0) return ByteArray(0)
        val samples = samples(pcm16)
        if (samples.size < 2) return ByteArray(0)
        val step = from.toDouble() / to
        val count = (samples.size / step).toInt()
        val out = ShortArray(count)
        for (index in 0 until count) {
            val position = index * step
            val lower = minOf(position.toInt(), samples.size - 1)
            val upper = minOf(lower + 1, samples.size - 1)
            val fraction = position - lower
            val value = samples[lower] * (1 - fraction) + samples[upper] * fraction
            out[index] = value.roundToInt().coerceIn(-32_768, 32_767).toShort()
        }
        return bytes(out)
    }

    /** Silence of `seconds` at `sampleRate`. */
    fun silence(seconds: Double, sampleRate: Int): ByteArray = ByteArray((sampleRate * seconds).toInt() * 2)

    /** Bytes of `data` split into chunks of at most `size`. */
    fun chunks(data: ByteArray, size: Int): List<ByteArray> {
        if (size <= 0 || data.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
        return (data.indices step size).map { data.copyOfRange(it, minOf(it + size, data.size)) }
    }

    /** A growing PCM buffer capped to its newest `capacity` bytes (pre-roll). */
    class Ring(private val capacity: Int) {
        private val buffer = ByteArray(capacity)
        private var start = 0
        private var count = 0

        fun append(bytes: ByteArray, length: Int = bytes.size) {
            for (i in 0 until length) {
                val end = (start + count) % capacity
                buffer[end] = bytes[i]
                if (count < capacity) count++ else start = (start + 1) % capacity
            }
        }

        fun snapshot(): ByteArray {
            val out = ByteArray(count)
            for (i in 0 until count) out[i] = buffer[(start + i) % capacity]
            return out
        }

        fun clear() { start = 0; count = 0 }

        val size: Int get() = count

        /** Moves the oldest bytes, at most [length], into [into]; the number moved. */
        fun take(into: ByteArray, length: Int = into.size): Int {
            val moved = minOf(length, count, into.size)
            for (i in 0 until moved) into[i] = buffer[(start + i) % capacity]
            start = (start + moved) % capacity
            count -= moved
            return moved
        }
    }

    /** An append-only PCM buffer readable while it grows. */
    class Growing {
        private val stream = ByteArrayOutputStream()
        fun append(bytes: ByteArray, length: Int = bytes.size) = stream.write(bytes, 0, length)
        fun set(bytes: ByteArray) { stream.reset(); stream.write(bytes) }
        fun snapshot(): ByteArray = stream.toByteArray()
        val size: Int get() = stream.size()
        fun clear() = stream.reset()
    }
}

/** Timeouts Hermes Desktop uses for `/api/audio/transcribe`: a floor scaled by the payload, clamped at a cap. */
object TranscriptionTimeouts {
    const val MINIMUM_MS = 180_000L
    const val MAXIMUM_MS = 600_000L
    private const val MS_PER_DATA_URL_CHARACTER = 0.1

    fun forDataUrl(characters: Int): Long =
        minOf(MAXIMUM_MS, maxOf(MINIMUM_MS, Math.ceil(characters * MS_PER_DATA_URL_CHARACTER).toLong()))
}
