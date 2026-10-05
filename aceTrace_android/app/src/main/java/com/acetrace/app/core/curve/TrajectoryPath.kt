package com.acetrace.app.core.curve

import com.acetrace.app.core.model.Point2D

interface TrajectoryPath {
    /**
     * Evaluates a point along the trajectory for global parameter u in [0.0, 1.0].
     */
    fun point(u: Float): Point2D

    /**
     * Total arc length in normalized coordinates.
     */
    fun length(): Float

    /**
     * Polyline sampling of n equidistant points in parameter space.
     */
    fun sample(n: Int): List<Point2D> {
        if (n <= 1) return listOf(point(0f))
        val result = ArrayList<Point2D>(n)
        val step = 1.0f / (n - 1)
        for (i in 0 until n) {
            val u = (i * step).coerceIn(0f, 1f)
            result.add(point(u))
        }
        return result
    }
}
