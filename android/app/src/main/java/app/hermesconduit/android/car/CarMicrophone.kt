package app.hermesconduit.android.car

import android.annotation.SuppressLint
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import app.hermesconduit.android.core.audio.ExternalMicrophone

/**
 * The car's microphone through Android Auto (`CarAudioRecord`, 16 kHz mono
 * PCM16, the same format as the phone capture). Each listening window opens a
 * fresh recording: a stopped CarAudioRecord can't be started again.
 */
class CarMicrophone(private val carContext: CarContext) : ExternalMicrophone {
    @Volatile private var record: CarAudioRecord? = null

    /** The coordinator asks the driver for the microphone before Voice starts. */
    @SuppressLint("MissingPermission")
    override fun start() {
        val audio = CarAudioRecord.create(carContext)
        audio.startRecording()
        record = audio
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        val audio = record ?: return -1
        return audio.read(buffer, offset, size)
    }

    override fun stop() {
        val audio = record ?: return
        record = null
        runCatching { audio.stopRecording() }
    }

    companion object {
        /** CarAudioRecord needs Car App API level 5. */
        fun isAvailable(carContext: CarContext) = carContext.carAppApiLevel >= 5
    }
}
