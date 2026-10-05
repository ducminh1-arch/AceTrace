import XCTest
@testable import AceTrace

final class TimeMappingGoldenTests: XCTestCase {

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

    func testTimeMappingAgainstGoldenVector() throws {
        let data = try loadGoldenJSON(named: "time_mapping_golden.json")
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let fStart = json["f_start"] as? Int,
              let fApex = json["f_apex"] as? Int,
              let fLanding = json["f_landing"] as? Int,
              let samplesArray = json["samples"] as? [[String: Any]] else {
            XCTFail("Invalid time_mapping_golden.json format")
            return
        }

        for sDict in samplesArray {
            guard let frame = sDict["frame"] as? Int,
              let expProgress = sDict["progress"] as? Double,
              let expU = sDict["u"] as? Double else { continue }

            let state = TimeMapping.computeFlightState(
                frameIndex: frame,
                startFrame: fStart,
                apexFrame: fApex,
                landingFrame: fLanding
            )

            XCTAssertEqual(state.u, expU, accuracy: 1e-4, "TimeMapping u mismatch at frame=\(frame)")
            XCTAssertEqual(state.progress, expProgress, accuracy: 1e-4, "TimeMapping progress mismatch at frame=\(frame)")
        }
    }
}
