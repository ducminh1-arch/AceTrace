import Foundation
import AVFoundation
import Combine

public struct iOSPlayerState {
    public var currentFrameIndex: Int = 0
    public var currentTimeMs: Int64 = 0
    public var isPlaying: Bool = false
    public var totalFrames: Int = 0
    public var isReady: Bool = false
}

public class PlayerController: ObservableObject {
    @Published public var state = iOSPlayerState()
    public let player = AVPlayer()

    private var metadata: VideoMetadata?
    private var timeObserver: Any?

    public init() {}

    public func loadVideo(metadata: VideoMetadata) {
        self.metadata = metadata
        let url: URL? = {
            if let u = URL(string: metadata.uri), u.scheme != nil {
                return u
            }
            return URL(fileURLWithPath: metadata.uri)
        }()
        guard let validURL = url else { return }

        let item = AVPlayerItem(url: validURL)
        player.replaceCurrentItem(with: item)

        state.totalFrames = metadata.frameCount
        state.isReady = true

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 30),
            queue: .main
        ) { [weak self] time in
            guard let self = self, let meta = self.metadata else { return }
            let ptsUs = Int64(time.seconds * 1_000_000)
            let frame = FrameIndexer.findNearestFrameIndex(ptsUsList: meta.ptsUs, targetPtsUs: ptsUs)
            self.state.currentFrameIndex = frame
            self.state.currentTimeMs = Int64(time.seconds * 1000)
        }
    }

    public func seekToFrame(_ frameIndex: Int) {
        guard let meta = metadata, !meta.ptsUs.isEmpty else { return }
        let clamped = min(max(frameIndex, 0), meta.frameCount - 1)
        let ptsUs = meta.ptsUs[clamped]
        let time = CMTime(value: ptsUs, timescale: 1_000_000)

        // Exact seek with zero tolerance
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            self?.state.currentFrameIndex = clamped
        }
    }

    public func step(byCount delta: Int) {
        if let currentItem = player.currentItem, currentItem.canStepForward && delta > 0 {
            currentItem.step(byCount: delta)
            state.currentFrameIndex = min(state.currentFrameIndex + delta, (metadata?.frameCount ?? 1) - 1)
        } else if let currentItem = player.currentItem, currentItem.canStepBackward && delta < 0 {
            currentItem.step(byCount: delta)
            state.currentFrameIndex = max(state.currentFrameIndex + delta, 0)
        } else {
            seekToFrame(state.currentFrameIndex + delta)
        }
    }

    public func play() {
        player.play()
        state.isPlaying = true
    }

    public func pause() {
        player.pause()
        state.isPlaying = false
    }

    public func togglePlay() {
        if state.isPlaying {
            pause()
        } else {
            play()
        }
    }

    deinit {
        if let observer = timeObserver {
            player.removeTimeObserver(observer)
        }
    }
}
