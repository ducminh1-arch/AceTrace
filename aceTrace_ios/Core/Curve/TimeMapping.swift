import Foundation

public enum FlightStatus: String, Codable {
    case beforeStart = "before_start"
    case ascending = "ascending"
    case descending = "descending"
    case landed = "landed"
}

public struct FlightState: Equatable {
    public let frameIndex: Int
    public let progress: Double
    public let u: Double
    public let status: FlightStatus

    public init(frameIndex: Int, progress: Double, u: Double, status: FlightStatus) {
        self.frameIndex = frameIndex
        self.progress = progress
        self.u = u
        self.status = status
    }
}

public enum TimeMapping {

    public static func computeFlightState(
        frameIndex: Int,
        startFrame: Int,
        apexFrame: Int,
        landingFrame: Int
    ) -> FlightState {
        if frameIndex < startFrame {
            return FlightState(frameIndex: frameIndex, progress: 0.0, u: 0.0, status: .beforeStart)
        }
        if frameIndex > landingFrame {
            return FlightState(frameIndex: frameIndex, progress: 1.0, u: 1.0, status: .landed)
        }

        if frameIndex <= apexFrame {
            let totalAscent = max(Double(apexFrame - startFrame), 1.0)
            let s = Double(frameIndex - startFrame) / totalAscent
            let sEased = 1.0 - (1.0 - s) * (1.0 - s)
            let u = min(max(0.5 * sEased, 0.0), 0.5)
            return FlightState(frameIndex: frameIndex, progress: u, u: u, status: .ascending)
        } else {
            let totalDescent = max(Double(landingFrame - apexFrame), 1.0)
            let s = Double(frameIndex - apexFrame) / totalDescent
            let sEased = s * s
            let u = min(max(0.5 + 0.5 * sEased, 0.5), 1.0)
            return FlightState(frameIndex: frameIndex, progress: u, u: u, status: .descending)
        }
    }

    public static func computeFlightStateFromKeypoints(
        frameIndex: Int,
        keypoints: [Keypoint]
    ) -> FlightState {
        let startKp = keypoints.first { $0.role == "start" } ?? keypoints.first
        let landingKp = keypoints.last { $0.role == "landing" } ?? keypoints.last
        let apexKp = keypoints.first { $0.role == "apex" }
            ?? (keypoints.count >= 3 ? keypoints[1] : nil)

        let startFrame = startKp?.frameIndex ?? 0
        let landingFrame = landingKp?.frameIndex ?? startFrame
        let apexFrame = apexKp?.frameIndex ?? ((startFrame + landingFrame) / 2)

        return computeFlightState(
            frameIndex: frameIndex,
            startFrame: startFrame,
            apexFrame: apexFrame,
            landingFrame: landingFrame
        )
    }

    public static func computeDistance(
        totalDistance: Double,
        progress: Double,
        easing: String = "easeOut"
    ) -> Double {
        let clamped = min(max(progress, 0.0), 1.0)
        let factor: Double
        switch easing {
        case "linear":
            factor = clamped
        case "easeInOut":
            if clamped < 0.5 {
                factor = 2.0 * clamped * clamped
            } else {
                let term = -2.0 * clamped + 2.0
                factor = 1.0 - (term * term) / 2.0
            }
        default: // easeOut
            factor = 1.0 - (1.0 - clamped) * (1.0 - clamped)
        }
        return totalDistance * factor
    }
}
