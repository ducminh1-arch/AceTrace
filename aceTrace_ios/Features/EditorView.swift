import SwiftUI
import AVFoundation
import PhotosUI

public struct EditorView: View {
    @StateObject private var playerController = PlayerController()
    @State private var project: Project
    @State private var selectedRole: String = "start"

    // Demo Playback & Animation state
    @State private var isDemoMode: Bool = true
    @State private var demoFrameIndex: Int = 0
    @State private var isDemoPlaying: Bool = false
    @State private var isAnimatedSwingMode: Bool = true
    @State private var selectedGolferIndex: Int = 1 // 1: Navy dress (Animated), 2: White polo, 3: Red polo

    // Navigation & Sheet states
    @State private var isShowingSpikeTest = false
    @State private var isShowingCamera = false
    @State private var isShowingPhotoPicker = false
    @State private var exportProgress: Double?
    @State private var exportAlertMessage: String?
    @State private var isShowingExportAlert = false

    // Timer for demo playback loop
    let timer = Timer.publish(every: 0.033, on: .main, in: .common).autoconnect()

    public init(initialProject: Project) {
        _project = State(initialValue: initialProject)
    }

    private var currentFrame: Int {
        isDemoMode ? demoFrameIndex : playerController.state.currentFrameIndex
    }

    private var totalFrames: Int {
        isDemoMode ? 120 : playerController.state.totalFrames
    }

    private var isPlaying: Bool {
        isDemoMode ? isDemoPlaying : playerController.state.isPlaying
    }

    // Dynamic golfer image name based on frame and mode
    private var activeGolferImageName: String {
        if isAnimatedSwingMode && selectedGolferIndex == 1 {
            switch currentFrame {
            case 0..<24:
                return "swing_1" // Address setup
            case 24..<46:
                return "swing_2" // Backswing
            case 46..<54:
                return "swing_3" // Impact strike
            default:
                return "swing_4" // Follow-through finish
            }
        } else {
            switch selectedGolferIndex {
            case 1: return "golf_1"
            case 2: return "golf_2"
            case 3: return "golf_3"
            default: return "golf_1"
            }
        }
    }

    public var body: some View {
        ZStack {
            Color(red: 0.05, green: 0.05, blue: 0.07).ignoresSafeArea()

            VStack(spacing: 0) {
                // Video Viewport + Overlay Canvas
                GeometryReader { geo in
                    ZStack {
                        Color.black

                        // 1. Background Video or Animated Golfer Photo
                        if isDemoMode {
                            Image(activeGolferImageName)
                                .resizable()
                                .scaledToFit()
                                .frame(width: geo.size.width, height: geo.size.height)
                        } else {
                            VideoPlayerLayerRepresentable(player: playerController.player)
                                .frame(width: geo.size.width, height: geo.size.height)
                        }

                        // 2. Real-time Trajectory Canvas
                        Canvas { context, size in
                            context.withCGContext { cgContext in
                                OverlayRenderer.render(
                                    frameIndex: currentFrame,
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

                        // 3. Ultra-Compact Sleek Top Bar
                        VStack {
                            HStack {
                                // (X) Reset Button
                                Button(action: resetToDefault) {
                                    Image(systemName: "xmark")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(.white)
                                        .frame(width: 32, height: 32)
                                        .background(Color.black.opacity(0.60))
                                        .clipShape(Circle())
                                        .overlay(Circle().stroke(Color.white.opacity(0.25), lineWidth: 1))
                                }

                                Spacer()

                                // Toolbar Capsule
                                HStack(spacing: 5) {
                                    // Swing Motion Toggle
                                    Button(action: { isAnimatedSwingMode.toggle() }) {
                                        Text(isAnimatedSwingMode ? "🎬 Swing" : "📷 Ảnh")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundColor(isAnimatedSwingMode ? .black : .white)
                                            .padding(.horizontal, 7)
                                            .padding(.vertical, 3)
                                            .background(isAnimatedSwingMode ? Color(red: 0, green: 0.9, blue: 1.0) : Color.white.opacity(0.15))
                                            .cornerRadius(10)
                                    }

                                    // Golfer 1, 2, 3 Circular Chips
                                    HStack(spacing: 3) {
                                        ForEach([1, 2, 3], id: \.self) { idx in
                                            let isSel = selectedGolferIndex == idx
                                            Button(action: {
                                                selectedGolferIndex = idx
                                                if idx != 1 { isAnimatedSwingMode = false }
                                            }) {
                                                Text("\(idx)")
                                                    .font(.system(size: 9, weight: .bold))
                                                    .foregroundColor(.white)
                                                    .frame(width: 20, height: 20)
                                                    .background(isSel ? Color(red: 1.0, green: 0.18, blue: 0.58) : Color.white.opacity(0.20))
                                                    .clipShape(Circle())
                                            }
                                        }
                                    }

                                    Divider()
                                        .frame(height: 12)
                                        .background(Color.white.opacity(0.3))

                                    // Camera Record Button
                                    Button(action: { isShowingCamera = true }) {
                                        Text("📹 Quay")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 3)
                                            .background(Color(red: 1.0, green: 0.2, blue: 0.4))
                                            .cornerRadius(10)
                                    }

                                    // Open/Pick Video Button
                                    Button(action: { isShowingPhotoPicker = true }) {
                                        Text("Mở")
                                            .font(.system(size: 10, weight: .semibold))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 3)
                                            .background(Color.white.opacity(0.15))
                                            .cornerRadius(10)
                                    }

                                    // Spike Test Button
                                    Button(action: { isShowingSpikeTest = true }) {
                                        Text("Spike")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundColor(Color(red: 1.0, green: 0.25, blue: 0.51))
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 3)
                                            .background(Color(red: 1.0, green: 0.25, blue: 0.51).opacity(0.25))
                                            .cornerRadius(10)
                                    }

                                    // Export Button
                                    Button(action: startExport) {
                                        Text("Export")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundColor(.black)
                                            .padding(.horizontal, 7)
                                            .padding(.vertical, 3)
                                            .background(Color(red: 0, green: 0.9, blue: 1.0))
                                            .cornerRadius(10)
                                    }
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Color.black.opacity(0.65))
                                .cornerRadius(18)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 18)
                                        .stroke(Color.white.opacity(0.20), lineWidth: 1)
                                )
                            }
                            .padding(.horizontal, 10)
                            .padding(.top, 8)

                            Spacer()
                        }
                    }
                }
                .clipped()

                // Bottom Controls Deck
                VStack(spacing: 10) {
                    // Frame counter & Play/Step Controls
                    HStack {
                        Text("Frame: \(currentFrame) / \(totalFrames)")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(Color(red: 0, green: 0.9, blue: 1.0))

                        Spacer()

                        Button(action: { stepFrame(by: -1) }) {
                            Image(systemName: "backward.frame")
                                .font(.system(size: 16))
                                .foregroundColor(.white)
                        }

                        Button(action: togglePlay) {
                            Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                                .font(.system(size: 22))
                                .foregroundColor(Color(red: 0, green: 0.9, blue: 1.0))
                        }

                        Button(action: { stepFrame(by: 1) }) {
                            Image(systemName: "forward.frame")
                                .font(.system(size: 16))
                                .foregroundColor(.white)
                        }
                    }
                    .padding(.horizontal)

                    // Scrubber Slider
                    Slider(
                        value: Binding(
                            get: { Double(currentFrame) },
                            set: { seekToFrame(Int($0)) }
                        ),
                        in: 0...Double(max(totalFrames - 1, 1))
                    )
                    .accentColor(Color(red: 0, green: 0.9, blue: 1.0))
                    .padding(.horizontal)

                    // Keypoint selection buttons: Start, Apex, Landing
                    HStack(spacing: 8) {
                        ForEach(["start", "apex", "landing"], id: \.self) { role in
                            let isSel = selectedRole == role
                            Button(action: { selectedRole = role }) {
                                Text(role.capitalized)
                                    .font(.caption)
                                    .fontWeight(.bold)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(isSel ? Color(red: 0, green: 0.9, blue: 1.0) : Color(red: 0.15, green: 0.15, blue: 0.22))
                                    .foregroundColor(isSel ? .black : .white)
                                    .cornerRadius(8)
                            }
                        }
                    }
                    .padding(.horizontal)

                    // Trail Mode Tabs: Tracer, Full, Comet
                    HStack(spacing: 8) {
                        ForEach(["tracer", "full", "comet"], id: \.self) { mode in
                            let currentMode = project.trajectories.first?.style.trailMode ?? "tracer"
                            let isSel = currentMode == mode
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
                                    .background(isSel ? Color(red: 0, green: 0.9, blue: 1.0).opacity(0.3) : Color.clear)
                                    .foregroundColor(isSel ? Color(red: 0, green: 0.9, blue: 1.0) : .gray)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 6)
                                            .stroke(isSel ? Color(red: 0, green: 0.9, blue: 1.0) : Color.gray.opacity(0.3), lineWidth: 1)
                                    )
                            }
                        }
                    }
                    .padding(.horizontal)
                }
                .padding(.vertical, 10)
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
        .onReceive(timer) { _ in
            if isDemoPlaying {
                demoFrameIndex = (demoFrameIndex + 1) % totalFrames
            }
        }
        .sheet(isPresented: $isShowingSpikeTest) {
            SpikeTestView()
        }
        .fullScreenCover(isPresented: $isShowingCamera) {
            CameraVideoRecorder(onVideoRecorded: loadVideoURL)
        }
        .sheet(isPresented: $isShowingPhotoPicker) {
            PhotoLibraryVideoPicker(onVideoPicked: loadVideoURL)
        }
        .alert("Export Notice", isPresented: $isShowingExportAlert) {
            Button("OK") {}
        } message: {
            Text(exportAlertMessage ?? "")
        }
    }

    private func togglePlay() {
        if isDemoMode {
            isDemoPlaying.toggle()
        } else {
            playerController.togglePlay()
        }
    }

    private func stepFrame(by delta: Int) {
        if isDemoMode {
            demoFrameIndex = min(max(demoFrameIndex + delta, 0), totalFrames - 1)
        } else {
            playerController.step(byCount: delta)
        }
    }

    private func seekToFrame(_ frame: Int) {
        if isDemoMode {
            demoFrameIndex = min(max(frame, 0), totalFrames - 1)
        } else {
            playerController.seekToFrame(frame)
        }
    }

    private func resetToDefault() {
        demoFrameIndex = 0
        isDemoPlaying = false
        project = Project.createDemoProject(orientation: "vertical")
    }

    private func updateKeypoint(role: String, x: Double, y: Double) {
        guard var traj = project.trajectories.first else { return }
        var kps = traj.keypoints
        let newKp = Keypoint(role: role, frameIndex: currentFrame, x: x, y: y)
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

    private func loadVideoURL(_ url: URL) {
        Task {
            do {
                let metadata = try await FrameIndexer.indexVideo(url: url)
                await MainActor.run {
                    self.project.video = metadata
                    if !self.project.trajectories.isEmpty {
                        self.project.trajectories[0].keypoints = [
                            Keypoint(role: "start", frameIndex: 0, x: 0.44, y: 0.76),
                            Keypoint(role: "apex", frameIndex: max(metadata.frameCount / 2, 1), x: 0.58, y: 0.18),
                            Keypoint(role: "landing", frameIndex: max(metadata.frameCount - 1, 2), x: 0.58, y: 0.52)
                        ]
                    }
                    self.playerController.loadVideo(metadata: metadata)
                    self.isDemoMode = false
                    self.isDemoPlaying = false
                }
            } catch {
                await MainActor.run {
                    self.exportAlertMessage = "Lỗi đọc video: \(error.localizedDescription)"
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

struct CameraVideoRecorder: UIViewControllerRepresentable {
    var onVideoRecorded: (URL) -> Void
    @Environment(\.presentationMode) var presentationMode

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            picker.sourceType = .camera
            picker.mediaTypes = ["public.movie"]
            picker.videoQuality = .typeHigh
            picker.cameraCaptureMode = .video
        }
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let parent: CameraVideoRecorder

        init(_ parent: CameraVideoRecorder) {
            self.parent = parent
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let url = info[.mediaURL] as? URL {
                parent.onVideoRecorded(url)
            }
            parent.presentationMode.wrappedValue.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.presentationMode.wrappedValue.dismiss()
        }
    }
}

struct PhotoLibraryVideoPicker: UIViewControllerRepresentable {
    var onVideoPicked: (URL) -> Void
    @Environment(\.presentationMode) var presentationMode

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .photoLibrary
        picker.mediaTypes = ["public.movie"]
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let parent: PhotoLibraryVideoPicker

        init(_ parent: PhotoLibraryVideoPicker) {
            self.parent = parent
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let url = info[.mediaURL] as? URL {
                parent.onVideoPicked(url)
            }
            parent.presentationMode.wrappedValue.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.presentationMode.wrappedValue.dismiss()
        }
    }
}
#endif
