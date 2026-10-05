import Foundation

public struct CatmullRomSegment: Equatable {
    public let p0: Point2D
    public let p1: Point2D
    public let p2: Point2D
    public let p3: Point2D
    public let alpha: Double

    private let t0: Double = 0.0
    private let t1: Double
    private let t2: Double
    private let t3: Double

    public init(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D, alpha: Double = 0.5) {
        self.p0 = p0
        self.p1 = p1
        self.p2 = p2
        self.p3 = p3
        self.alpha = alpha

        func distSq(_ a: Point2D, _ b: Point2D) -> Double {
            let dx = a.x - b.x
            let dy = a.y - b.y
            return dx * dx + dy * dy
        }

        var d01 = pow(distSq(p0, p1), alpha * 0.5)
        if d01 < 1e-4 { d01 = 1e-4 }
        self.t1 = 0.0 + d01

        var d12 = pow(distSq(p1, p2), alpha * 0.5)
        if d12 < 1e-4 { d12 = 1e-4 }
        self.t2 = self.t1 + d12

        var d23 = pow(distSq(p2, p3), alpha * 0.5)
        if d23 < 1e-4 { d23 = 1e-4 }
        self.t3 = self.t2 + d23
    }

    public func evaluate(tNormalized: Double) -> Point2D {
        let t = t1 + min(max(tNormalized, 0.0), 1.0) * (t2 - t1)

        func interp(_ a: Point2D, _ b: Point2D, _ ta: Double, _ tb: Double, _ tVal: Double) -> Point2D {
            let factor = (tVal - ta) / (tb - ta)
            return Point2D(
                x: a.x + factor * (b.x - a.x),
                y: a.y + factor * (b.y - a.y)
            )
        }

        let a1 = interp(p0, p1, t0, t1, t)
        let a2 = interp(p1, p2, t1, t2, t)
        let a3 = interp(p2, p3, t2, t3, t)

        let b1 = interp(a1, a2, t0, t2, t)
        let b2 = interp(a2, a3, t1, t3, t)

        return interp(b1, b2, t1, t2, t)
    }
}

public class CatmullRomPath: TrajectoryPath {
    public let segments: [CatmullRomSegment]
    private lazy var cachedLength: Double = computeLength(samples: 100)

    public init(segments: [CatmullRomSegment]) {
        self.segments = segments
    }

    public func point(u: Double) -> Point2D {
        guard !segments.isEmpty else { return Point2D(x: 0, y: 0) }
        let clampedU = min(max(u, 0.0), 1.0)
        let count = segments.count
        if count == 1 {
            return segments[0].evaluate(tNormalized: clampedU)
        }

        let scaled = clampedU * Double(count)
        var index = Int(scaled)
        if index >= count {
            index = count - 1
        }
        let localU = scaled - Double(index)
        return segments[index].evaluate(tNormalized: localU)
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

    public static func fromPoints(_ points: [Point2D], alpha: Double = 0.5) -> CatmullRomPath {
        guard points.count >= 2 else { return CatmullRomPath(segments: []) }

        var extended: [Point2D] = []
        extended.append(points.first!)
        extended.append(contentsOf: points)
        extended.append(points.last!)

        var segments: [CatmullRomSegment] = []
        for i in 0..<(extended.count - 3) {
            segments.append(
                CatmullRomSegment(
                    p0: extended[i],
                    p1: extended[i + 1],
                    p2: extended[i + 2],
                    p3: extended[i + 3],
                    alpha: alpha
                )
            )
        }
        return CatmullRomPath(segments: segments)
    }
}
