package app.hermesconduit.android.core.audio

/** Constants for the adaptive speech detector (iOS `VoiceSpeechDetectorConstants`). Levels are linear PCM peaks, 0…1. */
data class VoiceSpeechDetectorConstants(
    val noiseFloorSpeechRiseFactor: Float = 3f,
    val minimumSpeechStartThreshold: Float = 0.012f,
    val maximumSpeechStartThreshold: Float = 0.075f,
    val speechContinuationRiseFactor: Float = 1.6f,
    val minimumSpeechContinuationThreshold: Float = 0.006f,
    val speechStartDebounceSamples: Int = 2,
    val coldStartMaximumEvents: Int = 16,
    val coldStartCandidateSamples: Int = 4,
    val coldStartMinimumDynamicRange: Float = 0.012f,
    val coldStartQuietAdaptationAlpha: Float = 0.4f,
    val noiseFloorFallAlpha: Float = 0.2f,
    val noiseFloorRiseAlpha: Float = 0.06f,
    val minimumNoiseFloor: Float = 0.003f,
    val maximumNoiseFloor: Float = 0.5f,
)

enum class VoiceSpeechDetection { NONE, CONTINUED, STARTED }

/**
 * Adaptive listening-side speech detection (issue #130): learns the ambient
 * noise floor of the capture window and accepts speech that rises clearly
 * above it, never more sensitive than the conservative barge-in threshold.
 */
class VoiceSpeechDetector(val constants: VoiceSpeechDetectorConstants = VoiceSpeechDetectorConstants()) {
    var noiseFloor: Float = constants.minimumNoiseFloor
        private set
    private var coldStartRemaining = constants.coldStartMaximumEvents
    private val coldStartWindow = ArrayDeque<Float>()
    private var candidateSamples = 0
    var isSpeechActive = false
        private set

    private val speechContinuationThreshold: Float
        get() = maxOf(constants.minimumSpeechContinuationThreshold, noiseFloor * constants.speechContinuationRiseFactor)

    private val speechStartThreshold: Float
        get() = maxOf(constants.minimumSpeechStartThreshold, minOf(constants.maximumSpeechStartThreshold, noiseFloor * constants.noiseFloorSpeechRiseFactor))

    fun observe(level: Float): VoiceSpeechDetection {
        if (!level.isFinite() || level < 0) {
            candidateSamples = 0
            return VoiceSpeechDetection.NONE
        }
        if (isSpeechActive) {
            return if (level >= speechContinuationThreshold) VoiceSpeechDetection.CONTINUED else VoiceSpeechDetection.NONE
        }
        if (coldStartRemaining > 0) {
            coldStartRemaining -= 1
            if (level >= constants.maximumSpeechStartThreshold) {
                isSpeechActive = true
                return VoiceSpeechDetection.STARTED
            }
            if (level < constants.minimumSpeechStartThreshold) {
                adaptNoiseFloor(level, constants.coldStartQuietAdaptationAlpha, constants.minimumSpeechStartThreshold)
                coldStartWindow.clear()
                return VoiceSpeechDetection.NONE
            }
            coldStartWindow.addLast(level)
            if (coldStartWindow.size > constants.coldStartCandidateSamples) coldStartWindow.removeFirst()
            if (coldStartWindow.size == constants.coldStartCandidateSamples) {
                val loudest = coldStartWindow.max()
                val quietest = coldStartWindow.min()
                if (loudest - quietest >= constants.coldStartMinimumDynamicRange) {
                    isSpeechActive = true
                    coldStartWindow.clear()
                    return VoiceSpeechDetection.STARTED
                }
            }
            if (coldStartRemaining == 0 && coldStartWindow.isNotEmpty()) {
                noiseFloor = minOf(constants.maximumNoiseFloor, maxOf(constants.minimumNoiseFloor, coldStartWindow.sum() / coldStartWindow.size))
                coldStartWindow.clear()
            }
            return VoiceSpeechDetection.NONE
        }
        if (level >= constants.maximumSpeechStartThreshold) {
            isSpeechActive = true
            candidateSamples = 0
            return VoiceSpeechDetection.STARTED
        }
        if (level < speechStartThreshold) {
            candidateSamples = 0
            adaptNoiseFloor(level, if (level < noiseFloor) constants.noiseFloorFallAlpha else constants.noiseFloorRiseAlpha, constants.maximumNoiseFloor)
            return VoiceSpeechDetection.NONE
        }
        candidateSamples += 1
        if (candidateSamples < constants.speechStartDebounceSamples) return VoiceSpeechDetection.NONE
        isSpeechActive = true
        candidateSamples = 0
        return VoiceSpeechDetection.STARTED
    }

    fun reset() {
        noiseFloor = constants.minimumNoiseFloor
        coldStartRemaining = constants.coldStartMaximumEvents
        coldStartWindow.clear()
        candidateSamples = 0
        isSpeechActive = false
    }

    private fun adaptNoiseFloor(level: Float, alpha: Float, ceiling: Float) {
        noiseFloor += (level - noiseFloor) * alpha
        noiseFloor = minOf(ceiling, maxOf(constants.minimumNoiseFloor, noiseFloor))
    }
}
