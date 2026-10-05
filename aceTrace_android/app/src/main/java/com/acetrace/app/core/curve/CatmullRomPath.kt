package com.acetrace.app.core.curve

import com.acetrace.app.core.model.Point2D
import kotlin.math.pow
import kotlin.math.sqrt

data class CatmullRomSegment(
    val p0: Point2D,
    val p1: Point2D,
    val p2: Point2D,
    val p3: Point2D,
    val alpha: Float = 0.5f
) {
    private val t0 = 0.0f
    private val t1: Float
    private val t2: Float
    private val t3: Float

    init {
        fun distSq(a: Point2D, b: Point2D): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return dx * dx + dy * dy
        }

        var d01 = distSq(p0, p1).toDouble().pow(alpha * 0.5).toFloat()
        if (d01 < 1e-4f) d01 = 1e-4f
        t1 = t0 + d01

        var d12 = distSq(p1, p2).toDouble().pow(alpha * 0.5).toFloat()
        if (d12 < 1e-4f) d12 = 1e-4f
        t2 = t1 + d12

        var d23 = distSq(p2, p3).toDouble().pow(alpha * 0.5).toFloat()
        if (d23 < 1e-4f) d23 = 1e-4f
        t3 = t2 + d23
    }

    fun evaluate(tNormalized: Float): Point2D {
        val t = (t1 + tNormalized.coerceIn(0f, 1f) * (t2 - t1))

        fun interp(a: Point2D, b: Point2D, ta: Float, tb: Float, tVal: Float): Point2D {
            val factor = (tVal - ta) / (tb - ta)
            return Point2D(
                a.x + factor * (b.x - a.x),
                a.y + factor * (b.y - a.y)
            )
        }

        val a1 = interp(p0, p1, t0, t1, t)
        val a2 = interp(p1, p2, t1, t2, t)
        val a3 = interp(p2, p3, t2, t3, t)

        val b1 = interp(a1, a2, t0, t2, t)
        val b2 = interp(a2, a3, t1, t3, t)

        return interp(b1, b2, t1, t2, t)
    }
}

class CatmullRomPath(val segments: List<CatmullRomSegment>) : TrajectoryPath {

    private val cachedLength: Float by lazy {
        computeLength(100)
    }

    override fun point(u: Float): Point2D {
        if (segments.isEmpty()) return Point2D(0f, 0f)
        val clampedU = u.coerceIn(0f, 1f)
        val count = segments.size
        if (count == 1) {
            return segments[0].evaluate(clampedU)
        }

        val scaled = clampedU * count
        var index = scaled.toInt()
        if (index >= count) {
            index = count - 1
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
        fun fromPoints(points: List<Point2D>, alpha: Float = 0.5f): CatmullRomPath {
            if (points.size < 2) return CatmullRomPath(emptyList())

            // Duplicate start and end points for clamped boundary conditions
            val extended = mutableListOf<Point2D>()
            extended.add(points.first())
            extended.addAll(points)
            extended.add(points.last())

            val segments = mutableListOf<CatmullRomSegment>()
            for (i in 0 until extended.size - 3) {
                segments.add(
                    CatmullRomSegment(
                        extended[i],
                        extended[i + 1],
                        extended[i + 2],
                        extended[i + 3],
                        alpha
                    )
                )
            }
            return CatmullRomPath(segments)
        }
    }
}
