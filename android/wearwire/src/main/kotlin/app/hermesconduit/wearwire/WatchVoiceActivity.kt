package app.hermesconduit.wearwire

/**
 * Finds the last moment of speech in a PCM16 stream from 20 ms frame levels
 * against a slowly tracked noise floor (port of the iOS `WatchVoiceActivity`):
 * a reply's time is measured from it, and job news waits for the user to be
 * quiet.
 */
class WatchVoiceActivity {
    var noiseFloor = 0.003f
        private set
    var lastVoicedAtMs: Long? = null
        private set
    /** The level of the newest frame, 0…1. */
    var level = 0f
        private set

    /** [endingAtMs] is when the chunk's last sample was captured or arrived. */
    fun process(samples: ShortArray, sampleRate: Int, endingAtMs: Long) {
        val frameLength = maxOf(1, (sampleRate * 0.02).toInt())
        val frameCount = samples.size / frameLength
        if (frameCount <= 0) return
        for (frame in 0 until frameCount) {
            var sum = 0f
            val start = frame * frameLength
            for (i in start until start + frameLength) {
                val value = samples[i] / 32_768f
                sum += value * value
            }
            val rms = kotlin.math.sqrt(sum / frameLength)
            level = rms
            val frameEnd = endingAtMs - (frameCount - frame - 1) * 20L
            if (rms > maxOf(MINIMUM_SPEECH_LEVEL, noiseFloor * SPEECH_OVER_FLOOR)) lastVoicedAtMs = frameEnd
            // Down quickly, up slowly: speech never becomes the floor.
            noiseFloor = if (rms < noiseFloor) noiseFloor * 0.5f + rms * 0.5f else minOf(0.05f, noiseFloor * 1.002f)
        }
    }

    fun reset() {
        lastVoicedAtMs = null
        level = 0f
    }

    companion object {
        /** Below this a frame never counts as speech (about -42 dBFS). */
        const val MINIMUM_SPEECH_LEVEL = 0.008f
        /** Speech must sit this far above the noise floor (about 12 dB). */
        const val SPEECH_OVER_FLOOR = 4f
    }
}
