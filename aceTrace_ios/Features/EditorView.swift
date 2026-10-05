import SwiftUI
import AVFoundation

public struct EditorView: View {
    @StateObject private var playerController = PlayerController()
    @State private var project: Project
    @State private var selectedRole: String = "start"
    @State private var exportProgress: Double?
    @State private var exportAlertMessage: String?
    @State private var isShowingExportAlert = false

    public init(initialProject: Project) {
        _project = State(initialValue: initialProject)
    }

    public var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.08).ignoresSafeArea()

            VStack(spacing: 0) {
                // Top Header
                HStack {
                    Text("AceTrace Shot Tracker")
                        .font(.headline)
                        .foregroundColor(.white)
                    Spacer()
                    Button(action: startExport) {
                        HStack {
                            Image(systemName: "arrow.down.circle.fill")
                            Text("Export")
                                .fontWeight(.bold)
                        }
                        .foregroundColor(.black)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color(red: 0, green: 0.9, blue: 1.0))
                        .cornerRadius(8)
                    }
                }
                .padding()
                .background(Color(red: 0.08, green: 0.08, blue: 0.12))

                // Video Viewport + Overlay Canvas
                GeometryReader { geo in
                    ZStack {
                        Color.black

                        // Native Video Layer
                        VideoPlayerLayerRepresentable(player: playerController.player)
                            .frame(width: geo.size.width, height: geo.size.height)

                        // Real-time Overlay Canvas using pure OverlayRenderer
                        Canvas { context, size in
                            context.withCGContext { cgContext in
                                OverlayRenderer.render(
                                    frameIndex: playerController.state.currentFrameIndex,
                                    project: project,
                                    context: cgContext,
                                    canvasSize: size
                                )
                            }
                        }
                        .frame(width: geo.size.width, height: geo.size.height)
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { value in
                                    let normX = min(max(value.location.x / geo.size.width, 0.0), 1.0)
                                    let normY = min(max(value.location.y / geo.size.height, 0.0), 1.0)
                                    updateKeypoint(role: selectedRole, x: normX, y: normY)
                                }
                        )
                    }
                }
                .clipped()

                // Bottom Controls
                VStack(spacing: 12) {
                    // Frame counter & Play/Step Controls
                    HStack {
                        Text("Frame: \(playerController.state.currentFrameIndex) / \(playerController.state.totalFrames)")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.white)

                        Spacer()

                        Button(action: { playerController.step(byCount: -1) }) {
                            Image(systemName: "backward.frame")
                                .foregroundColor(.white)
                        }

                        Button(action: { playerController.togglePlay() }) {
                            Image(systemName: playerController.state.isPlaying ? "pause.fill" : "play.fill")
                                .foregroundColor(Color(red: 0, green: 0.9, blue: 1.0))
                                .font(.title2)
                        }

                        Button(action: { playerController.step(byCount: 1) }) {
                            Image(systemName: "forward.frame")
                                .foregroundColor(.white)
                        }
                    }

                    // Scrubber
                    if playerController.state.totalFrames > 1 {
                        Slider(
                            value: Binding(
                                get: { Double(playerController.state.currentFrameIndex) },
                                set: { playerController.seekToFrame(Int($0)) }
                            ),
                            in: 0...Double(max(playerController.state.totalFrames - 1, 1))
                        )
                        .accentColor(Color(red: 0, green: 0.9, blue: 1.0))
                    }

                    // Keypoint selection buttons
                    HStack(spacing: 8) {
                        ForEach(["start", "apex", "landing"], id: \.self) { role in
                            Button(action: { selectedRole = role }) {
                                Text(role.capitalized)
                                    .font(.caption)
                                    .fontWeight(.bold)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(selectedRole == role ? Color(red: 0, green: 0.9, blue: 1.0) : Color(red: 0.15, green: 0.15, blue: 0.22))
                                    .foregroundColor(selectedRole == role ? .black : .white)
                                    .cornerRadius(8)
                            }
                        }
                    }

                    // Trail Mode Tabs
                    HStack(spacing: 8) {
                        ForEach(["tracer", "full", "comet"], id: \.self) { mode in
                            let currentMode = project.trajectories.first?.style.trailMode ?? "tracer"
                            Button(action: {
                                if var traj = project.trajectories.first {
                                    traj.style.trailMode = mode
                                    project.trajectories = [traj]
                                }
                            }) {
                                Text(mode.capitalized)
                                    .font(.caption2)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 6)
                                    .background(currentMode == mode ? Color(red: 0, green: 0.9, blue: 1.0).opacity(0.3) : Color.clear)
                                    .foregroundColor(currentMode == mode ? Color(red: 0, green: 0.9, blue: 1.0) : .gray)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 6)
                                            .stroke(currentMode == mode ? Color(red: 0, green: 0.9, blue: 1.0) : Color.gray.opacity(0.3), lineWidth: 1)
                                    )
                            }
                        }
                    }
                }
                .padding()
                .background(Color(red: 0.08, green: 0.08, blue: 0.12))
            }

            // Export Progress Overlay
            if let progress = exportProgress {
                Color.black.opacity(0.7).ignoresSafeArea()
                VStack(spacing: 16) {
                    ProgressView(value: progress, total: 1.0)
                        .progressViewStyle(LinearProgressViewStyle(tint: Color(red: 0, green: 0.9, blue: 1.0)))
                        .frame(width: 200)
                    Text("Exporting: \(Int(progress * 100))%")
                        .foregroundColor(.white)
                        .fontWeight(.bold)
                }
                .padding(24)
                .background(Color(red: 0.12, green: 0.12, blue: 0.18))
                .cornerRadius(12)
            }
        }
        .onAppear {
            playerController.loadVideo(metadata: project.video)
        }
        .alert("Export Notice", isPresented: $isShowingExportAlert) {
            Button("OK") {}
        } message: {
            Text(exportAlertMessage ?? "")
        }
    }

    private func updateKeypoint(role: String, x: Double, y: Double) {
        guard var traj = project.trajectories.first else { return }
        var kps = traj.keypoints
        let newKp = Keypoint(role: role, frameIndex: playerController.state.currentFrameIndex, x: x, y: y)
        if let idx = kps.firstIndex(where: { $0.role == role }) {
            kps[idx] = newKp
        } else {
            kps.append(newKp)
        }
        traj.keypoints = kps
        project.trajectories = [traj]
    }

    private func startExport() {
        exportProgress = 0.0
        Task {
            let exporter = VideoExporter()
            let outputURL = FileManager.default.temporaryDirectory.appendingPathComponent("acetrace_export_\(UUID().uuidString).mp4")
            do {
                try await exporter.exportVideo(project: project, outputURL: outputURL) { progress in
                    DispatchQueue.main.async {
                        self.exportProgress = progress
                    }
                }
                DispatchQueue.main.async {
                    self.exportProgress = nil
                    self.exportAlertMessage = "Exported successfully to: \(outputURL.lastPathComponent)"
                    self.isShowingExportAlert = true
                }
            } catch {
                DispatchQueue.main.async {
                    self.exportProgress = nil
                    self.exportAlertMessage = "Export failed: \(error.localizedDescription)"
                    self.isShowingExportAlert = true
                }
            }
        }
    }
}

#if canImport(UIKit)
struct VideoPlayerLayerRepresentable: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        let playerLayer = AVPlayerLayer(player: player)
        playerLayer.videoGravity = .resizeAspect
        view.layer.addSublayer(playerLayer)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        if let playerLayer = uiView.layer.sublayers?.first as? AVPlayerLayer {
            playerLayer.frame = uiView.bounds
            playerLayer.player = player
        }
    }
}
#endif
