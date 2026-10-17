package app.hermesconduit.android.car

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays the [CarEarcon] tones (iOS CarPlayEarconPlayer). Each tone is
 * synthesized once into a static AudioTrack and replayed from the start.
 * Classic mode only, like iOS: the microphone is the classic pipeline's.
 */
class CarEarconPlayer {
    private val tracks = HashMap<CarEarcon, AudioTrack>()

    fun play(earcon: CarEarcon) {
        val track = track(earcon) ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    fun release() {
        tracks.values.forEach { runCatching { it.release() } }
        tracks.clear()
    }

    private fun track(earcon: CarEarcon): AudioTrack? {
        tracks[earcon]?.let { return it }
        val samples = earcon.samples()
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CarEarcon.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
                .also { track ->
                    track.write(samples, 0, samples.size)
                    track.setVolume(VOLUME)
                }
        }.getOrNull() ?: return null
        tracks[earcon] = track
        return track
    }

    private companion object {
        const val VOLUME = 0.35f
    }
}
