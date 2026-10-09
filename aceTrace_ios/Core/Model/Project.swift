import Foundation
import CoreGraphics

public struct Point2D: Codable, Equatable {
    public var x: Double
    public var y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }

    public var cgPoint: CGPoint {
        CGPoint(x: x, y: y)
    }
}

public struct VideoMetadata: Codable, Equatable {
    public var uri: String
    public var width: Int
    public var height: Int
    public var rotationDegrees: Int
    public var durationUs: Int64
    public var frameCount: Int
    public var ptsUs: [Int64]

    public init(
        uri: String,
        width: Int,
        height: Int,
        rotationDegrees: Int = 0,
        durationUs: Int64 = 0,
        frameCount: Int = 0,
        ptsUs: [Int64] = []
    ) {
        self.uri = uri
        self.width = width
        self.height = height
        self.rotationDegrees = rotationDegrees
        self.durationUs = durationUs
        self.frameCount = frameCount
        self.ptsUs = ptsUs
    }
}

public struct Keypoint: Codable, Equatable {
    public var role: String // "start", "apex", "landing", "waypoint"
    public var frameIndex: Int
    public var x: Double
    public var y: Double
    public var handleIn: Point2D?
    public var handleOut: Point2D?

    public init(
        role: String,
        frameIndex: Int,
        x: Double,
        y: Double,
        handleIn: Point2D? = nil,
        handleOut: Point2D? = nil
    ) {
        self.role = role
        self.frameIndex = frameIndex
        self.x = x
        self.y = y
        self.handleIn = handleIn
        self.handleOut = handleOut
    }
}

public struct TrajectoryStyle: Codable, Equatable {
    public var palette: String
    public var gradient: [String]
    public var lineWidth: Double
    public var glow: Double
    public var trailMode: String // "full", "tracer", "comet"
    public var cometLengthFraction: Double
    public var showImpactFlash: Bool

    public init(
        palette: String = "neonCyan",
        gradient: [String] = ["#00E5FF", "#FF2D95", "#FFB300"],
        lineWidth: Double = 6.0,
        glow: Double = 0.7,
        trailMode: String = "tracer",
        cometLengthFraction: Double = 0.25,
        showImpactFlash: Bool = true
    ) {
        self.palette = palette
        self.gradient = gradient
        self.lineWidth = lineWidth
        self.glow = glow
        self.trailMode = trailMode
        self.cometLengthFraction = cometLengthFraction
        self.showImpactFlash = showImpactFlash
    }
}

public struct DistanceConfig: Codable, Equatable {
    public var value: Double
    public var unit: String // "yd", "ft", "m"
    public var visible: Bool
    public var easing: String

    public init(
        value: Double = 250.0,
        unit: String = "yd",
        visible: Bool = true,
        easing: String = "easeOut"
    ) {
        self.value = value
        self.unit = unit
        self.visible = visible
        self.easing = easing
    }
}

public struct Trajectory: Codable, Equatable, Identifiable {
    public var id: String
    public var sport: String
    public var mode: String // "bezier", "catmullRom"
    public var keypoints: [Keypoint]
    public var style: TrajectoryStyle
    public var distance: DistanceConfig

    public init(
        id: String = UUID().uuidString,
        sport: String = "golf",
        mode: String = "bezier",
        keypoints: [Keypoint] = [],
        style: TrajectoryStyle = TrajectoryStyle(),
        distance: DistanceConfig = DistanceConfig()
    ) {
        self.id = id
        self.sport = sport
        self.mode = mode
        self.keypoints = keypoints
        self.style = style
        self.distance = distance
    }
}

public struct CameraTransform: Codable, Equatable {
    public var frameIndex: Int
    public var matrix: [Double] // 9 elements
}

public struct CameraTrack: Codable, Equatable {
    public var referenceFrameIndex: Int
    public var transforms: [CameraTransform]
}

public struct ExportConfig: Codable, Equatable {
    public var resolution: String // "source", "1080p", "720p", "4k"
    public var fps: String // "source", "30", "60"
    public var watermark: Bool

    public init(
        resolution: String = "source",
        fps: String = "source",
        watermark: Bool = true
    ) {
        self.resolution = resolution
        self.fps = fps
        self.watermark = watermark
    }
}

public struct Project: Codable, Equatable, Identifiable {
    public var schemaVersion: Int
    public var id: String
    public var createdAt: String
    public var video: VideoMetadata
    public var trajectories: [Trajectory]
    public var cameraTrack: CameraTrack?
    public var export: ExportConfig

    public init(
        schemaVersion: Int = 1,
        id: String = UUID().uuidString,
        createdAt: String = ISO8601DateFormatter().string(from: Date()),
        video: VideoMetadata,
        trajectories: [Trajectory] = [],
        cameraTrack: CameraTrack? = nil,
        export: ExportConfig = ExportConfig()
    ) {
        self.schemaVersion = schemaVersion
        self.id = id
        self.createdAt = createdAt
        self.video = video
        self.trajectories = trajectories
        self.cameraTrack = cameraTrack
        self.export = export
    }
}

extension Project {
    public static func createDemoProject(orientation: String = "vertical") -> Project {
        let isVert = orientation == "vertical"
        let demoPts = (0...120).map { Int64($0 * 33333) }
        return Project(
            video: VideoMetadata(
                uri: "demo",
                width: isVert ? 1080 : 1920,
                height: isVert ? 1920 : 1080,
                rotationDegrees: 0,
                durationUs: 4_000_000,
                frameCount: 120,
                ptsUs: demoPts
            ),
            trajectories: [
                Trajectory(
                    sport: "golf",
                    mode: "bezier",
                    keypoints: isVert ? [
                        Keypoint(role: "start", frameIndex: 48, x: 0.46, y: 0.77),
                        Keypoint(role: "apex", frameIndex: 78, x: 0.58, y: 0.18),
                        Keypoint(role: "landing", frameIndex: 112, x: 0.58, y: 0.52)
                    ] : [
                        Keypoint(role: "start", frameIndex: 48, x: 0.32, y: 0.82),
                        Keypoint(role: "apex", frameIndex: 78, x: 0.52, y: 0.22),
                        Keypoint(role: "landing", frameIndex: 112, x: 0.74, y: 0.65)
                    ],
                    style: TrajectoryStyle(
                        palette: "aurora",
                        gradient: ["#00E5FF", "#FF2D95", "#FFB300"],
                        lineWidth: 11.0,
                        glow: 0.85,
                        trailMode: "tracer"
                    ),
                    distance: DistanceConfig(
                        value: 450.0,
                        unit: "ft",
                        visible: true,
                        easing: "easeOut"
                    )
                )
            ]
        )
    }
}
