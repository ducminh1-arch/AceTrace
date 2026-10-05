package com.acetrace.app.core.curve

import com.acetrace.app.core.model.Keypoint
import com.acetrace.app.core.model.Point2D
import kotlin.math.sqrt

data class BezierSegment(
    val p0: Point2D,
    val p1: Point2D,
    val p2: Point2D,
    val p3: Point2D
) {
    fun evaluate(t: Float): Point2D {
        val u = t.coerceIn(0f, 1f)
        val oneMinusU = 1f - u
        val c0 = oneMinusU * oneMinusU * oneMinusU
        val c1 = 3f * oneMinusU * oneMinusU * u
        val c2 = 3f * oneMinusU * u * u
        val c3 = u * u * u

        val x = c0 * p0.x + c1 * p1.x + c2 * p2.x + c3 * p3.x
        val y = c0 * p0.y + c1 * p1.y + c2 * p2.y + c3 * p3.y
        return Point2D(x, y)
    }
}

class BezierPath(val segments: List<BezierSegment>) : TrajectoryPath {

    private val cachedLength: Float by lazy {
        computeLength(100)
    }

    override fun point(u: Float): Point2D {
        if (segments.isEmpty()) return Point2D(0f, 0f)
        val clampedU = u.coerceIn(0f, 1f)
        val segmentCount = segments.size
        if (segmentCount == 1) {
            return segments[0].evaluate(clampedU)
        }

        val scaled = clampedU * segmentCount
        var index = scaled.toInt()
        if (index >= segmentCount) {
            index = segmentCount - 1
        }
        val localU = scaled - index
        return segments[index].evaluate(localU)
    }

    override fun length(): Float = cachedLength

    private fun computeLength(samples: Int): Float {
        if (segments.isEmpty()) return 0f
        var sum = 0f
        var prev = point(0f)
        val step = 1.0f / samples
        for (i in 1..samples) {
            val curr = point(i * step)
            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            sum += sqrt(dx * dx + dy * dy)
            prev = curr
        }
        return sum
    }

    companion object {
        /**
         * Builds a BezierPath from keypoints. Automatically applies default handles if not specified.
         */
        fun fromKeypoints(keypoints: List<Keypoint>): BezierPath {
            if (keypoints.size < 2) return BezierPath(emptyList())

            val resolvedKeypoints = resolveHandles(keypoints)
            val segments = mutableListOf<BezierSegment>()

            for (i in 0 until resolvedKeypoints.size - 1) {
                val kpA = resolvedKeypoints[i]
                val kpB = resolvedKeypoints[i + 1]

                val p0 = Point2D(kpA.x, kpA.y)
                val p1 = kpA.handleOut ?: Point2D(
                    kpA.x + (kpB.x - kpA.x) / 3f,
                    kpA.y + (kpB.y - kpA.y) / 3f
                )
                val p2 = kpB.handleIn ?: Point2D(
                    kpB.x - (kpB.x - kpA.x) / 3f,
                    kpB.y - (kpB.y - kpA.y) / 3f
                )
                val p3 = Point2D(kpB.x, kpB.y)

                segments.add(BezierSegment(p0, p1, p2, p3))
            }

            return BezierPath(segments)
        }

        /**
         * Resolves handles for keypoints according to the canonical specification:
         * For 3 keypoints (Start -> Apex -> Landing):
         * Start: handleOut towards Apex (1/3 dist)
         * Apex: horizontal tangent handleIn and handleOut
         * Landing: handleIn towards Apex (1/3 dist)
         */
        fun resolveHandles(keypoints: List<Keypoint>): List<Keypoint> {
            if (keypoints.size != 3) {
                return keypoints.mapIndexed { idx, kp ->
                    val defaultIn = kp.handleIn ?: if (idx > 0) {
                        val prev = keypoints[idx - 1]
                        Point2D(kp.x - (kp.x - prev.x) / 3f, kp.y - (kp.y - prev.y) / 3f)
                    } else null
                    val defaultOut = kp.handleOut ?: if (idx < keypoints.size - 1) {
                        val next = keypoints[idx + 1]
                        Point2D(kp.x + (next.x - kp.x) / 3f, kp.y + (next.y - kp.y) / 3f)
                    } else null
                    kp.copy(handleIn = defaultIn, handleOut = defaultOut)
                }
            }

            val start = keypoints[0]
            val apex = keypoints[1]
            val landing = keypoints[2]

            val startHandleOut = start.handleOut ?: Point2D(
                start.x + (apex.x - start.x) / 3f,
                start.y + (apex.y - start.y) / 3f
            )

            val apexHandleIn = apex.handleIn ?: Point2D(
                apex.x - (apex.x - start.x) / 3f,
                apex.y
            )

            val apexHandleOut = apex.handleOut ?: Point2D(
                apex.x + (landing.x - apex.x) / 3f,
                apex.y
            )

            val landingHandleIn = landing.handleIn ?: Point2D(
                landing.x - (landing.x - apex.x) / 3f,
                landing.y - (landing.y - apex.y) / 3f
            )

            return listOf(
                start.copy(handleIn = null, handleOut = startHandleOut),
                apex.copy(handleIn = apexHandleIn, handleOut = apexHandleOut),
                landing.copy(handleIn = landingHandleIn, handleOut = null)
            )
        }
    }
}
