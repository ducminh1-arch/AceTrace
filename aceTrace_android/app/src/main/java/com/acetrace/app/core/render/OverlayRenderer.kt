package com.acetrace.app.core.render

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.RectF
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
        canvasHeight: Float,
        contentRect: RectF = RectF(0f, 0f, canvasWidth, canvasHeight)
    ) {
        for (trajectory in project.trajectories) {
            renderTrajectory(frameIndex, trajectory, canvas, contentRect)
        }

        // Render Watermark if required by export config
        if (project.export.watermark) {
            renderWatermark(canvas, contentRect)
        }
    }

    private fun renderTrajectory(
        frameIndex: Int,
        trajectory: Trajectory,
        canvas: Canvas,
        contentRect: RectF
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
        val rx = contentRect.left
        val ry = contentRect.top
        val rw = contentRect.width()
        val rh = contentRect.height()
        for (i in 0..numSamples) {
            val u = (uStart + i * step).coerceIn(0f, 1f)
            val pt = path.point(u)
            samplePoints.add(Point2D(rx + pt.x * rw, ry + pt.y * rh))
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

        // 4b. Leading Edge Golf Ball Head Glow
        val headPt = samplePoints.last()
        val ballGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            if (baseLineWidth > 2f) {
                maskFilter = BlurMaskFilter(baseLineWidth * 0.7f, BlurMaskFilter.Blur.SOLID)
            }
        }
        canvas.drawCircle(headPt.x, headPt.y, baseLineWidth * 0.9f, ballGlowPaint)

        // 4c. Wave / Ribbon effect along trajectory (matching reference screenshots)
        if (trajStyle.effectMode == "wave" && samplePoints.size >= 2) {
            val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#B0FF2D95")
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = baseLineWidth * 0.8f
                maskFilter = BlurMaskFilter(baseLineWidth * 1.5f, BlurMaskFilter.Blur.NORMAL)
            }
            val wavePath = Path()
            for (i in samplePoints.indices) {
                val pt = samplePoints[i]
                val waveOffset = kotlin.math.sin(i * 0.65f + frameIndex * 0.25f) * (baseLineWidth * 2.2f)
                val wx = (pt.x + waveOffset).toFloat()
                val wy = (pt.y + (waveOffset * 0.35f)).toFloat()
                if (i == 0) wavePath.moveTo(wx, wy) else wavePath.lineTo(wx, wy)
            }
            canvas.drawPath(wavePath, wavePaint)
        }

        // 5. Dynamic Distance HUD label (above ball) - Only if NOT showing Hero Overlay
        if (trajectory.distance.visible && !trajectory.distance.showHeroOverlay) {
            renderDistanceLabel(
                flightState.progress,
                trajectory.distance.value,
                trajectory.distance.unit,
                trajectory.distance.easing,
                samplePoints.last(),
                canvas,
                contentRect
            )
        }

        // 6. Hero Large Distance Display on Video (matching reference screenshot 1 "450ft")
        if (trajectory.distance.visible && trajectory.distance.showHeroOverlay) {
            renderHeroDistanceOverlay(
                flightState.progress,
                trajectory.distance.value,
                trajectory.distance.unit,
                trajectory.distance.easing,
                canvas,
                contentRect,
                trajStyle.gradient.getOrNull(1) ?: "#FF2D95"
            )
        }
    }

    private fun renderHeroDistanceOverlay(
        progress: Float,
        totalDistance: Float,
        unit: String,
        easing: String,
        canvas: Canvas,
        contentRect: RectF,
        accentColorHex: String
    ) {
        val currentDist = TimeMapping.computeDistance(totalDistance, progress, easing)
        val heroText = "${currentDist.roundToInt()}$unit"

        val heroFontSize = (contentRect.height() * 0.085f).coerceIn(42f, 76f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = heroFontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(12f, 0f, 4f, Color.parseColor("#B3000000"))
        }

        val textWidth = textPaint.measureText(heroText)
        val posX = contentRect.left + 44f
        val posY = contentRect.top + heroFontSize * 1.6f

        canvas.drawText(heroText, posX, posY, textPaint)

        // Underline luminous accent streak (matching Screenshot 1)
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColorSafe(accentColorHex)
            style = Paint.Style.STROKE
            strokeWidth = (heroFontSize * 0.10f).coerceIn(4.5f, 9f)
            strokeCap = Paint.Cap.ROUND
            maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.SOLID)
        }
        val streakPath = Path().apply {
            val startY = posY + 14f
            moveTo(posX - 4f, startY)
            cubicTo(
                posX + textWidth * 0.35f, startY + 14f,
                posX + textWidth * 0.70f, startY + 10f,
                posX + textWidth * 1.15f, startY - 2f
            )
        }
        canvas.drawPath(streakPath, streakPaint)
    }

    private fun renderDistanceLabel(
        progress: Float,
        totalDistance: Float,
        unit: String,
        easing: String,
        headPoint: Point2D,
        canvas: Canvas,
        contentRect: RectF
    ) {
        val currentDist = TimeMapping.computeDistance(totalDistance, progress, easing)
        val labelText = "${currentDist.roundToInt()} $unit"

        val labelFontSize = (contentRect.height() * 0.045f).coerceIn(16f, 36f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = labelFontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val textWidth = textPaint.measureText(labelText)
        val textHeight = labelFontSize * 0.85f
        val padding = labelFontSize * 0.45f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E612121A")
            style = Paint.Style.FILL
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8000E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        // Clamped within contentRect
        val minX = contentRect.left + textWidth / 2f + padding
        val maxX = contentRect.right - textWidth / 2f - padding
        val centerX = if (minX < maxX) headPoint.x.coerceIn(minX, maxX) else (contentRect.left + contentRect.right) / 2f
        val centerY = (headPoint.y - labelFontSize * 1.3f).coerceIn(
            contentRect.top + textHeight + padding,
            contentRect.bottom - padding
        )

        val rectLeft = centerX - textWidth / 2f - padding
        val rectTop = centerY - textHeight / 2f - padding
        val rectRight = centerX + textWidth / 2f + padding
        val rectBottom = centerY + textHeight / 2f + padding

        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, 12f, 12f, bgPaint)
        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, 12f, 12f, borderPaint)
        canvas.drawText(labelText, centerX, centerY + textHeight / 3f, textPaint)
    }

    private fun renderWatermark(canvas: Canvas, contentRect: RectF) {
        val fontSize = (contentRect.height() * 0.022f).coerceIn(14f, 32f)
        val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 140
            textSize = fontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("Traced with AceTrace", contentRect.right - 20f, contentRect.bottom - 20f, watermarkPaint)
    }

    private fun parseColorSafe(hex: String): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.CYAN
        }
    }
}
