import XCTest
@testable import AceTrace

final class CurveGoldenTests: XCTestCase {

    private func loadGoldenJSON(named filename: String) throws -> Data {
        let testBundle = Bundle(for: type(of: self))
        if let url = testBundle.url(forResource: filename, withExtension: nil, subdirectory: "golden") {
            return try Data(contentsOf: url)
        }
        let filePath = URL(fileURLWithPath: #file)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("aceTrace_spec")
            .appendingPathComponent("golden")
            .appendingPathComponent(filename)
        return try Data(contentsOf: filePath)
    }

    func testBezierAgainstGoldenVector() throws {
        let data = try loadGoldenJSON(named: "bezier_golden.json")
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let keypointsArray = json["keypoints"] as? [[String: Any]],
              let samplesArray = json["samples"] as? [[String: Any]] else {
            XCTFail("Invalid bezier_golden.json format")
            return
        }

        let keypoints: [Keypoint] = keypointsArray.compactMap { dict in
            guard let role = dict["role"] as? String,
                  let pt = dict["point"] as? [String: Double] else { return nil }
            let hIn = (dict["handleIn"] as? [String: Double]).map { Point2D(x: $0["x"]!, y: $0["y"]!) }
            let hOut = (dict["handleOut"] as? [String: Double]).map { Point2D(x: $0["x"]!, y: $0["y"]!) }
            return Keypoint(role: role, frameIndex: 0, x: pt["x"]!, y: pt["y"]!, handleIn: hIn, handleOut: hOut)
        }

        let path = BezierPath.fromKeypoints(keypoints)

        for sDict in samplesArray {
            guard let u = sDict["u"] as? Double,
                  let expPt = sDict["point"] as? [String: Double] else { continue }
            let expX = expPt["x"]!
            let expY = expPt["y"]!

            let actual = path.point(u: u)
            XCTAssertEqual(actual.x, expX, accuracy: 1e-4, "Bezier X mismatch at u=\(u)")
            XCTAssertEqual(actual.y, expY, accuracy: 1e-4, "Bezier Y mismatch at u=\(u)")
        }
    }

    func testCatmullRomAgainstGoldenVector() throws {
        let data = try loadGoldenJSON(named: "catmull_rom_golden.json")
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let controlPoints = json["control_points"] as? [[String: Double]],
              let samplesArray = json["samples"] as? [[String: Any]] else {
            XCTFail("Invalid catmull_rom_golden.json format")
            return
        }

        let pts = controlPoints.map { Point2D(x: $0["x"]!, y: $0["y"]!) }
        let segment = CatmullRomSegment(p0: pts[0], p1: pts[1], p2: pts[2], p3: pts[3], alpha: 0.5)

        for sDict in samplesArray {
            guard let t = sDict["t"] as? Double,
                  let expPt = sDict["point"] as? [String: Double] else { continue }
            let expX = expPt["x"]!
            let expY = expPt["y"]!

            let actual = segment.evaluate(tNormalized: t)
            XCTAssertEqual(actual.x, expX, accuracy: 1e-4, "Catmull-Rom X mismatch at t=\(t)")
            XCTAssertEqual(actual.y, expY, accuracy: 1e-4, "Catmull-Rom Y mismatch at t=\(t)")
        }
    }
}
