package com.acetrace.app.core.render

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import com.acetrace.app.core.curve.BezierPath
import com.acetrace.app.core.curve.CatmullRomPath
import com.acetrace.app.core.curve.FlightStatus
import com.acetrace.app.core.curve.TimeMapping
import com.acetrace.app.core.curve.TrajectoryPath
import com.acetrace.app.core.model.Point2D
import com.acetrace.app.core.model.Project
import com.acetrace.app.core.model.Trajectory
import kotlin.math.roundToInt

object OverlayRenderer {

    /**
     * Pure rendering function: draws the overlay onto the given canvas at target dimensions.
     * Used identically by both real-time UI preview and video export pipelines.
     */
    fun render(
        frameIndex: Int,
        project: Project,
        canvas: Canvas,
        canvasWidth: Float,
        canvasHeight: Float
    ) {
        for (trajectory in project.trajectories) {
            renderTrajectory(frameIndex, trajectory, canvas, canvasWidth, canvasHeight)
        }

        // Render Watermark if required by export config
        if (project.export.watermark) {
            renderWatermark(canvas, canvasWidth, canvasHeight)
        }
    }

    private fun renderTrajectory(
        frameIndex: Int,
        trajectory: Trajectory,
        canvas: Canvas,
        width: Float,
        height: Float
    ) {
        val keypoints = trajectory.keypoints
        if (keypoints.size < 2) return

        val flightState = TimeMapping.computeFlightStateFromKeypoints(frameIndex, keypoints)
        if (flightState.status == FlightStatus.BEFORE_START) {
            // Before shot start: do not render trajectory
            return
        }

        val path: TrajectoryPath = when (trajectory.mode) {
            "catmullRom" -> {
                val pts = keypoints.map { Point2D(it.x, it.y) }
                CatmullRomPath.fromPoints(pts)
            }
            else -> BezierPath.fromKeypoints(keypoints)
        }

        val trajStyle = trajectory.style
        val currentU = flightState.u
        val trailMode = trajStyle.trailMode

        // Determine parameter range [uStart, uEnd] to draw
        val (uStart, uEnd) = when (trailMode) {
            "full" -> 0f to 1f
            "comet" -> {
                val cometLength = trajStyle.cometLengthFraction
                (currentU - cometLength).coerceAtLeast(0f) to currentU
            }
            else -> 0f to currentU // "tracer"
        }

        if (uEnd <= uStart && currentU > 0f) return

        // Generate polyline points
        val numSamples = 60
        val samplePoints = mutableListOf<Point2D>()
        val step = (uEnd - uStart) / numSamples.coerceAtLeast(1)
        for (i in 0..numSamples) {
            val u = (uStart + i * step).coerceIn(0f, 1f)
            val pt = path.point(u)
            samplePoints.add(Point2D(pt.x * width, pt.y * height))
        }

        if (samplePoints.size < 2) return

        // Build android.graphics.Path
        val drawPath = Path().apply {
            moveTo(samplePoints[0].x, samplePoints[0].y)
            for (i in 1 until samplePoints.size) {
                lineTo(samplePoints[i].x, samplePoints[i].y)
            }
        }

        // Colors & Gradient setup
        val parsedColors = trajStyle.gradient.map { parseColorSafe(it) }.toIntArray()
        val gradientShader = if (parsedColors.size >= 2) {
            val startPt = samplePoints.first()
            val endPt = samplePoints.last()
            LinearGradient(
                startPt.x, startPt.y, endPt.x, endPt.y,
                parsedColors, null, Shader.TileMode.CLAMP
            )
        } else null

        val baseLineWidth = trajStyle.lineWidth.coerceAtLeast(1f)
        val glowFactor = trajStyle.glow.coerceIn(0f, 1f)

        // 1. Layer 1: Outer Glow
        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = baseLineWidth * 3.5f
            shader = gradientShader
            color = parsedColors.firstOrNull() ?: Color.CYAN
            alpha = (255 * 0.20f * glowFactor).roundToInt().coerceIn(0, 255)
            if (glowFactor > 0.05f) {
                maskFilter = BlurMaskFilter(baseLineWidth * 2.0f, BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawPath(drawPath, outerPaint)

        // 2. Layer 2: Inner Glow
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = baseLineWidth * 1.8f
            shader = gradientShader
            color = parsedColors.firstOrNull() ?: Color.CYAN
            alpha = (255 * 0.45f * glowFactor).roundToInt().coerceIn(0, 255)
            if (glowFactor > 0.05f) {
                maskFilter = BlurMaskFilter(baseLineWidth * 0.8f, BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawPath(drawPath, innerPaint)

        // 3. Layer 3: Core Line (sharp & bright)
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = baseLineWidth
            shader = gradientShader
            color = parsedColors.firstOrNull() ?: Color.WHITE
            alpha = 255
        }
        canvas.drawPath(drawPath, corePaint)

        // 4. Impact Flash at Start point (if near start frame)
        if (trajStyle.showImpactFlash && flightState.progress < 0.15f) {
            val startPx = samplePoints.first()
            val flashAlpha = ((1f - flightState.progress / 0.15f) * 200).roundToInt().coerceIn(0, 255)
            val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = flashAlpha
                maskFilter = BlurMaskFilter(baseLineWidth * 3f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(startPx.x, startPx.y, baseLineWidth * 2.5f, flashPaint)
        }

        // 5. Dynamic Distance HUD label
        if (trajectory.distance.visible) {
            renderDistanceLabel(
                flightState.progress,
                trajectory.distance.value,
                trajectory.distance.unit,
                trajectory.distance.easing,
                samplePoints.last(),
                canvas
            )
        }
    }

    private fun renderDistanceLabel(
        progress: Float,
        totalDistance: Float,
        unit: String,
        easing: String,
        headPoint: Point2D,
        canvas: Canvas
    ) {
        val currentDist = TimeMapping.computeDistance(totalDistance, progress, easing)
        val labelText = "${currentDist.roundToInt()} $unit"

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val textWidth = textPaint.measureText(labelText)
        val textHeight = 24f
        val padding = 12f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC12121A")
            style = Paint.Style.FILL
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6600E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        // Offset -32px above head point
        val centerX = headPoint.x
        val centerY = headPoint.y - 36f

        val rectLeft = centerX - textWidth / 2f - padding
        val rectTop = centerY - textHeight / 2f - padding
        val rectRight = centerX + textWidth / 2f + padding
        val rectBottom = centerY + textHeight / 2f + padding

        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, 12f, 12f, bgPaint)
        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, 12f, 12f, borderPaint)
        canvas.drawText(labelText, centerX, centerY + textHeight / 3f, textPaint)
    }

    private fun renderWatermark(canvas: Canvas, width: Float, height: Float) {
        val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 140
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("Traced with AceTrace", width - 30f, height - 30f, watermarkPaint)
    }

    private fun parseColorSafe(hex: String): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.CYAN
        }
    }
}
