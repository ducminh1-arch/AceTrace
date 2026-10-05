import Foundation

public struct BezierSegment: Equatable {
    public let p0: Point2D
    public let p1: Point2D
    public let p2: Point2D
    public let p3: Point2D

    public init(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
        self.p0 = p0
        self.p1 = p1
        self.p2 = p2
        self.p3 = p3
    }

    public func evaluate(t: Double) -> Point2D {
        let u = min(max(t, 0.0), 1.0)
        let oneMinusU = 1.0 - u
        let c0 = oneMinusU * oneMinusU * oneMinusU
        let c1 = 3.0 * oneMinusU * oneMinusU * u
        let c2 = 3.0 * oneMinusU * u * u
        let c3 = u * u * u

        let x = c0 * p0.x + c1 * p1.x + c2 * p2.x + c3 * p3.x
        let y = c0 * p0.y + c1 * p1.y + c2 * p2.y + c3 * p3.y
        return Point2D(x: x, y: y)
    }
}

public class BezierPath: TrajectoryPath {
    public let segments: [BezierSegment]
    private lazy var cachedLength: Double = computeLength(samples: 100)

    public init(segments: [BezierSegment]) {
        self.segments = segments
    }

    public func point(u: Double) -> Point2D {
        guard !segments.isEmpty else { return Point2D(x: 0, y: 0) }
        let clampedU = min(max(u, 0.0), 1.0)
        let count = segments.count
        if count == 1 {
            return segments[0].evaluate(t: clampedU)
        }

        let scaled = clampedU * Double(count)
        var index = Int(scaled)
        if index >= count {
            index = count - 1
        }
        let localU = scaled - Double(index)
        return segments[index].evaluate(t: localU)
    }

    public func length() -> Double {
        return cachedLength
    }

    private func computeLength(samples: Int) -> Double {
        guard !segments.isEmpty else { return 0.0 }
        var sum = 0.0
        var prev = point(u: 0.0)
        let step = 1.0 / Double(samples)
        for i in 1...samples {
            let curr = point(u: Double(i) * step)
            let dx = curr.x - prev.x
            let dy = curr.y - prev.y
            sum += sqrt(dx * dx + dy * dy)
            prev = curr
        }
        return sum
    }

    public static func fromKeypoints(_ keypoints: [Keypoint]) -> BezierPath {
        guard keypoints.count >= 2 else { return BezierPath(segments: []) }
        let resolved = resolveHandles(keypoints)
        var segments: [BezierSegment] = []

        for i in 0..<(resolved.count - 1) {
            let kpA = resolved[i]
            let kpB = resolved[i + 1]

            let p0 = Point2D(x: kpA.x, y: kpA.y)
            let p1 = kpA.handleOut ?? Point2D(
                x: kpA.x + (kpB.x - kpA.x) / 3.0,
                y: kpA.y + (kpB.y - kpA.y) / 3.0
            )
            let p2 = kpB.handleIn ?? Point2D(
                x: kpB.x - (kpB.x - kpA.x) / 3.0,
                y: kpB.y - (kpB.y - kpA.y) / 3.0
            )
            let p3 = Point2D(x: kpB.x, y: kpB.y)

            segments.append(BezierSegment(p0: p0, p1: p1, p2: p2, p3: p3))
        }

        return BezierPath(segments: segments)
    }

    public static func resolveHandles(_ keypoints: [Keypoint]) -> [Keypoint] {
        guard keypoints.count == 3 else {
            return keypoints.enumerated().map { idx, kp in
                var updated = kp
                if updated.handleIn == nil && idx > 0 {
                    let prev = keypoints[idx - 1]
                    updated.handleIn = Point2D(x: kp.x - (kp.x - prev.x) / 3.0, y: kp.y - (kp.y - prev.y) / 3.0)
                }
                if updated.handleOut == nil && idx < keypoints.count - 1 {
                    let next = keypoints[idx + 1]
                    updated.handleOut = Point2D(x: kp.x + (next.x - kp.x) / 3.0, y: kp.y + (next.y - kp.y) / 3.0)
                }
                return updated
            }
        }

        let start = keypoints[0]
        let apex = keypoints[1]
        let landing = keypoints[2]

        let startHandleOut = start.handleOut ?? Point2D(
            x: start.x + (apex.x - start.x) / 3.0,
            y: start.y + (apex.y - start.y) / 3.0
        )

        let apexHandleIn = apex.handleIn ?? Point2D(
            x: apex.x - (apex.x - start.x) / 3.0,
            y: apex.y
        )

        let apexHandleOut = apex.handleOut ?? Point2D(
            x: apex.x + (landing.x - apex.x) / 3.0,
            y: apex.y
        )

        let landingHandleIn = landing.handleIn ?? Point2D(
            x: landing.x - (landing.x - apex.x) / 3.0,
            y: landing.y - (landing.y - apex.y) / 3.0
        )

        var s = start
        s.handleIn = nil
        s.handleOut = startHandleOut

        var a = apex
        a.handleIn = apexHandleIn
        a.handleOut = apexHandleOut

        var l = landing
        l.handleIn = landingHandleIn
        l.handleOut = nil

        return [s, a, l]
    }
}
