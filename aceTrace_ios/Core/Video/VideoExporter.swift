import Foundation
import AVFoundation
import CoreGraphics

public enum iOSExportState {
    case progress(Double)
    case success(URL)
    case failure(Error)
}

public class VideoExporter {

    public init() {}

    public func exportVideo(
        project: Project,
        outputURL: URL,
        onProgress: @escaping (Double) -> Void
    ) async throws {
        guard let inputURL = URL(string: project.video.uri) else {
            throw NSError(domain: "AceTrace", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid video URI"])
        }

        let asset = AVURLAsset(url: inputURL)
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw NSError(domain: "AceTrace", code: -2, userInfo: [NSLocalizedDescriptionKey: "No video track found"])
        }

        let size = try await videoTrack.load(.naturalSize)
        let transform = try await videoTrack.load(.preferredTransform)
        let duration = try await asset.load(.duration)

        // Setup Reader
        let reader = try AVAssetReader(asset: asset)
        let readerOutputSettings: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
        ]
        let videoReaderOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: readerOutputSettings)
        reader.add(videoReaderOutput)

        // Setup Writer
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try FileManager.default.removeItem(at: outputURL)
        }
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
        let videoWriterSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(size.width),
            AVVideoHeightKey: Int(size.height)
        ]
        let videoWriterInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoWriterSettings)
        videoWriterInput.expectsMediaDataInRealTime = false
        videoWriterInput.transform = transform

        let pixelBufferAdaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoWriterInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
                kCVPixelBufferWidthKey as String: Int(size.width),
                kCVPixelBufferHeightKey as String: Int(size.height)
            ]
        )
        writer.add(videoWriterInput)

        // Audio Passthrough Track if available
        var audioReaderOutput: AVAssetReaderTrackOutput?
        var audioWriterInput: AVAssetWriterInput?
        if let audioTrack = try await asset.loadTracks(withMediaType: .audio).first {
            let aReader = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: nil)
            let aWriter = AVAssetWriterInput(mediaType: .audio, outputSettings: nil)
            aWriter.expectsMediaDataInRealTime = false
            if reader.canAdd(aReader) && writer.canAdd(aWriter) {
                reader.add(aReader)
                writer.add(aWriter)
                audioReaderOutput = aReader
                audioWriterInput = aWriter
            }
        }

        reader.startReading()
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)

        // Writing video frames with overlay
        var totalFramesProcessed = 0
        let totalFramesCount = max(project.video.frameCount, 1)

        while reader.status == .reading {
            if videoWriterInput.isReadyForMoreMediaData {
                guard let sampleBuffer = videoReaderOutput.copyNextSampleBuffer() else { break }
                guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { continue }
                let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)

                CVPixelBufferLockBaseAddress(imageBuffer, [])
                let baseAddress = CVPixelBufferGetBaseAddress(imageBuffer)
                let bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer)
                let colorSpace = CGColorSpaceCreateDeviceRGB()

                if let context = CGContext(
                    data: baseAddress,
                    width: Int(size.width),
                    height: Int(size.height),
                    bitsPerComponent: 8,
                    bytesPerRow: bytesPerRow,
                    space: colorSpace,
                    bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
                ) {
                    let ptsUs = Int64(presentationTime.seconds * 1_000_000)
                    let frameIndex = FrameIndexer.findNearestFrameIndex(
                        ptsUsList: project.video.ptsUs,
                        targetPtsUs: ptsUs
                    )

                    // Draw identical overlay
                    OverlayRenderer.render(
                        frameIndex: frameIndex,
                        project: project,
                        context: context,
                        canvasSize: size
                    )
                }

                CVPixelBufferUnlockBaseAddress(imageBuffer, [])
                pixelBufferAdaptor.append(imageBuffer, withPresentationTime: presentationTime)

                totalFramesProcessed += 1
                let progress = min(Double(totalFramesProcessed) / Double(totalFramesCount), 1.0)
                onProgress(progress)
            }
        }

        // Copy audio samples
        if let aOut = audioReaderOutput, let aIn = audioWriterInput {
            while reader.status == .reading {
                if aIn.isReadyForMoreMediaData {
                    guard let sampleBuffer = aOut.copyNextSampleBuffer() else { break }
                    aIn.append(sampleBuffer)
                }
            }
        }

        videoWriterInput.markAsFinished()
        audioWriterInput?.markAsFinished()
        await writer.finishWriting()

        if writer.status == .failed, let error = writer.error {
            throw error
        }
    }
}
