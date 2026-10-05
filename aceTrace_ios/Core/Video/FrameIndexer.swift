import Foundation
import AVFoundation

public enum FrameIndexer {

    public static func indexVideo(url: URL) async throws -> VideoMetadata {
        let asset = AVURLAsset(url: url)
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw NSError(domain: "AceTrace", code: -1, userInfo: [NSLocalizedDescriptionKey: "No video track found"])
        }

        let size = try await videoTrack.load(.naturalSize)
        let transform = try await videoTrack.load(.preferredTransform)
        let duration = try await asset.load(.duration)

        // Compute rotation degrees from transform
        var rotation = 0
        if transform.a == 0 && transform.b == 1.0 && transform.c == -1.0 && transform.d == 0 {
            rotation = 90
        } else if transform.a == -1.0 && transform.b == 0 && transform.c == 0 && transform.d == -1.0 {
            rotation = 180
        } else if transform.a == 0 && transform.b == -1.0 && transform.c == 1.0 && transform.d == 0 {
            rotation = 270
        }

        let durationUs = Int64(duration.seconds * 1_000_000)

        // Read PTS array using AVAssetReader
        var ptsUsList: [Int64] = []
        let reader = try AVAssetReader(asset: asset)
        let outputSettings: [String: Any] = [:] // Empty settings to read raw sample timestamps without decoding full frames
        let trackOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: outputSettings)
        reader.add(trackOutput)
        reader.startReading()

        while let sampleBuffer = trackOutput.copyNextSampleBuffer() {
            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            if pts.isValid {
                ptsUsList.append(Int64(pts.seconds * 1_000_000))
            }
        }

        ptsUsList.sort()

        return VideoMetadata(
            uri: url.absoluteString,
            width: Int(size.width),
            height: Int(size.height),
            rotationDegrees: rotation,
            durationUs: durationUs,
            frameCount: max(ptsUsList.count, 1),
            ptsUs: ptsUsList
        )
    }

    public static func findNearestFrameIndex(ptsUsList: [Int64], targetPtsUs: Int64) -> Int {
        guard !ptsUsList.isEmpty else { return 0 }
        var low = 0
        var high = ptsUsList.count - 1

        while low <= high {
            let mid = (low + high) / 2
            let midVal = ptsUsList[mid]
            if midVal == targetPtsUs {
                return mid
            } else if midVal < targetPtsUs {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        if low <= 0 { return 0 }
        if low >= ptsUsList.count { return ptsUsList.count - 1 }

        let prev = ptsUsList[low - 1]
        let next = ptsUsList[low]
        return (targetPtsUs - prev <= next - targetPtsUs) ? (low - 1) : low
    }
}
