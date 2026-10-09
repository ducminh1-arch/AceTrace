package com.acetrace.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Point2D(
    val x: Float,
    val y: Float
)

@Serializable
data class VideoMetadata(
    val uri: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val durationUs: Long = 0L,
    val frameCount: Int = 0,
    val ptsUs: List<Long> = emptyList()
)

@Serializable
data class Keypoint(
    val role: String, // "start", "apex", "landing", "waypoint"
    val frameIndex: Int,
    val x: Float,
    val y: Float,
    val handleIn: Point2D? = null,
    val handleOut: Point2D? = null
)

@Serializable
data class TrajectoryStyle(
    val palette: String = "neonCyan",
    val gradient: List<String> = listOf("#00E5FF", "#FF2D95", "#FFB300"),
    val lineWidth: Float = 6f,
    val glow: Float = 0.7f,
    val trailMode: String = "tracer", // "full", "tracer", "comet"
    val cometLengthFraction: Float = 0.25f,
    val showImpactFlash: Boolean = true,
    val effectMode: String = "standard", // "standard", "wave", "sparkle"
    val is3D: Boolean = true
)

@Serializable
data class DistanceConfig(
    val value: Float = 250f,
    val unit: String = "yd", // "yd", "ft", "m"
    val visible: Boolean = true,
    val easing: String = "easeOut",
    val showHeroOverlay: Boolean = true // Large "450ft" on video as seen in reference screenshots
)

@Serializable
data class Trajectory(
    val id: String,
    val sport: String = "golf",
    val mode: String = "bezier", // "bezier", "catmullRom"
    val keypoints: List<Keypoint> = emptyList(),
    val style: TrajectoryStyle = TrajectoryStyle(),
    val distance: DistanceConfig = DistanceConfig()
)

@Serializable
data class CameraTransform(
    val frameIndex: Int,
    val matrix: List<Float> // 3x3 matrix (9 elements)
)

@Serializable
data class CameraTrack(
    val referenceFrameIndex: Int = 0,
    val transforms: List<CameraTransform> = emptyList()
)

@Serializable
data class ExportConfig(
    val resolution: String = "source", // "source", "1080p", "720p", "4k"
    val fps: String = "source", // "source", "30", "60"
    val watermark: Boolean = true
)

@Serializable
data class Project(
    val schemaVersion: Int = 1,
    val id: String,
    val createdAt: String,
    val video: VideoMetadata,
    val trajectories: List<Trajectory> = emptyList(),
    val cameraTrack: CameraTrack? = null,
    val export: ExportConfig = ExportConfig()
)
