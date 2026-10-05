package com.acetrace.app.core.curve

import com.acetrace.app.core.model.Keypoint

enum class FlightStatus {
    BEFORE_START,
    ASCENDING,
    DESCENDING,
    LANDED
}

data class FlightState(
    val frameIndex: Int,
    val progress: Float, // [0.0, 1.0]
    val u: Float,        // Parameter on curve [0.0, 1.0]
    val status: FlightStatus
)

object TimeMapping {

    fun computeFlightState(
        frameIndex: Int,
        startFrame: Int,
        apexFrame: Int,
        landingFrame: Int
    ): FlightState {
        if (frameIndex < startFrame) {
            return FlightState(frameIndex, 0f, 0f, FlightStatus.BEFORE_START)
        }
        if (frameIndex > landingFrame) {
            return FlightState(frameIndex, 1f, 1f, FlightStatus.LANDED)
        }

        if (frameIndex <= apexFrame) {
            val totalAscent = (apexFrame - startFrame).coerceAtLeast(1)
            val s = (frameIndex - startFrame).toFloat() / totalAscent
            // Ease-out on ascent (decelerating against gravity)
            val sEased = 1f - (1f - s) * (1f - s)
            val u = (0.5f * sEased).coerceIn(0f, 0.5f)
            return FlightState(frameIndex, u, u, FlightStatus.ASCENDING)
        } else {
            val totalDescent = (landingFrame - apexFrame).coerceAtLeast(1)
            val s = (frameIndex - apexFrame).toFloat() / totalDescent
            // Ease-in on descent (accelerating due to gravity)
            val sEased = s * s
            val u = (0.5f + 0.5f * sEased).coerceIn(0.5f, 1f)
            return FlightState(frameIndex, u, u, FlightStatus.DESCENDING)
        }
    }

    fun computeFlightStateFromKeypoints(frameIndex: Int, keypoints: List<Keypoint>): FlightState {
        val startKp = keypoints.firstOrNull { it.role == "start" } ?: keypoints.firstOrNull()
        val landingKp = keypoints.lastOrNull { it.role == "landing" } ?: keypoints.lastOrNull()
        val apexKp = keypoints.firstOrNull { it.role == "apex" }
            ?: (if (keypoints.size >= 3) keypoints[1] else null)

        val startFrame = startKp?.frameIndex ?: 0
        val landingFrame = landingKp?.frameIndex ?: startFrame
        val apexFrame = apexKp?.frameIndex ?: ((startFrame + landingFrame) / 2)

        return computeFlightState(frameIndex, startFrame, apexFrame, landingFrame)
    }

    /**
     * Calculates displayed distance based on progress and easing.
     */
    fun computeDistance(totalDistance: Float, progress: Float, easing: String = "easeOut"): Float {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val easedFactor = when (easing) {
            "linear" -> clampedProgress
            "easeInOut" -> {
                if (clampedProgress < 0.5f) 2f * clampedProgress * clampedProgress
                else 1f - (-2f * clampedProgress + 2f).let { it * it } / 2f
            }
            else -> 1f - (1f - clampedProgress) * (1f - clampedProgress) // easeOut
        }
        return totalDistance * easedFactor
    }
}
