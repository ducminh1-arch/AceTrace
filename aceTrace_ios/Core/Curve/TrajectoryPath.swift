import Foundation

public protocol TrajectoryPath {
    func point(u: Double) -> Point2D
    func length() -> Double
    func sample(n: Int) -> [Point2D]
}

public extension TrajectoryPath {
    func sample(n: Int) -> [Point2D] {
        guard n > 1 else { return [point(u: 0.0)] }
        var result = [Point2D]()
        result.reserveCapacity(n)
        let step = 1.0 / Double(n - 1)
        for i in 0..<n {
            let u = min(max(Double(i) * step, 0.0), 1.0)
            result.append(point(u: u))
        }
        return result
    }
}
