package com.acetrace.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.acetrace.app.core.model.*
import com.acetrace.app.core.render.OverlayRenderer
import com.acetrace.app.core.video.ExportState
import com.acetrace.app.core.video.FrameIndexer
import com.acetrace.app.core.video.PlayerController
import com.acetrace.app.core.video.VideoExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

fun computeVideoContentRect(
    containerW: Float,
    containerH: Float,
    videoW: Int,
    videoH: Int,
    rotationDegrees: Int = 0
): RectF {
    val effectiveW = if (rotationDegrees == 90 || rotationDegrees == 270) videoH.toFloat() else videoW.toFloat()
    val effectiveH = if (rotationDegrees == 90 || rotationDegrees == 270) videoW.toFloat() else videoH.toFloat()
    if (effectiveW <= 0f || effectiveH <= 0f || containerW <= 0f || containerH <= 0f) {
        return RectF(0f, 0f, containerW, containerH)
    }
    val scale = minOf(containerW / effectiveW, containerH / effectiveH)
    val w = effectiveW * scale
    val h = effectiveH * scale
    val x = (containerW - w) / 2f
    val y = (containerH - h) / 2f
    return RectF(x, y, x + w, y + h)
}

fun loadGolferBitmap(context: android.content.Context, assetName: String): android.graphics.Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        context.assets.open(assetName).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    } catch (_: Throwable) {
        null
    }
}

fun createDemoProject(orientation: String = "vertical"): Project {
    val isVert = orientation == "vertical"
    val demoPts = (0..120).map { (it * 33333L) }
    return Project(
        id = UUID.randomUUID().toString(),
        createdAt = "2026-10-06T00:00:00Z",
        video = VideoMetadata(
            uri = "demo",
            width = if (isVert) 1080 else 1920,
            height = if (isVert) 1920 else 1080,
            durationUs = 4_000_000L,
            frameCount = 120,
            ptsUs = demoPts
        ),
        trajectories = listOf(
            Trajectory(
                id = UUID.randomUUID().toString(),
                sport = "golf",
                mode = "bezier",
                // Curve tuned to match the reference screenshots:
                // Starts at tee box between feet, arcs up into the blue sky to the right, drops into the green fairway
                keypoints = if (isVert) {
                    listOf(
                        Keypoint("start", 48, 0.46f, 0.77f),
                        Keypoint("apex", 78, 0.58f, 0.18f),
                        Keypoint("landing", 112, 0.58f, 0.52f)
                    )
                } else {
                    listOf(
                        Keypoint("start", 48, 0.32f, 0.82f),
                        Keypoint("apex", 78, 0.52f, 0.22f),
                        Keypoint("landing", 112, 0.74f, 0.65f)
                    )
                },
                style = TrajectoryStyle(
                    palette = "aurora",
                    gradient = listOf("#00E5FF", "#FF2D95", "#FFB300"),
                    lineWidth = 11f,
                    glow = 0.85f,
                    trailMode = "tracer",
                    effectMode = "standard",
                    is3D = true
                ),
                distance = DistanceConfig(
                    value = 450f,
                    unit = "ft",
                    visible = true,
                    easing = "easeOut",
                    showHeroOverlay = false // Keep false so top area is completely clean!
                )
            )
        )
    )
}

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFFFF2D95),
                    background = Color(0xFF0C0C12),
                    surface = Color(0xFF14141E)
                )
            ) {
                AceTraceEditorScreen()
            }
        }
    }
}

@UnstableApi
@Composable
fun AceTraceEditorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Demo state (defaults to true with vertical golfer image)
    var isDemoMode by remember { mutableStateOf(true) }
    var demoOrientation by remember { mutableStateOf("vertical") } // "vertical" or "horizontal"
    var demoFrameIndex by remember { mutableStateOf(0) } // start at address frame so the entire swing animation plays
    var isDemoPlaying by remember { mutableStateOf(false) }

    // Active bottom toolbar tab: "style", "settings", "text", "effects", "render"
    var activeTab by remember { mutableStateOf("style") }
    var isAutoTracking by remember { mutableStateOf(false) }

    // 4-Phase Animated Swing Frames
    val swingBitmap1: android.graphics.Bitmap? = remember(context) { loadGolferBitmap(context, "swing_1.jpg") } // Address setup
    val swingBitmap2: android.graphics.Bitmap? = remember(context) { loadGolferBitmap(context, "swing_2.jpg") } // Backswing
    val swingBitmap3: android.graphics.Bitmap? = remember(context) { loadGolferBitmap(context, "swing_3.jpg") } // Impact strike
    val swingBitmap4: android.graphics.Bitmap? = remember(context) { loadGolferBitmap(context, "swing_4.jpg") ?: loadGolferBitmap(context, "golf_1.jpg") } // Follow-through

    val golferBitmap1: android.graphics.Bitmap? = remember(context) { swingBitmap4 ?: loadGolferBitmap(context, "golf_1.jpg") }
    val golferBitmap2: android.graphics.Bitmap? = remember(context) { loadGolferBitmap(context, "golf_2.jpg") }
    val golferBitmap3: android.graphics.Bitmap? = remember(context) { loadGolferBitmap(context, "golf_3.jpg") }
    val demoHorizontalBitmap: android.graphics.Bitmap? = remember(context) { loadGolferBitmap(context, "golf_horizontal.jpg") }

    var isAnimatedSwingMode by remember { mutableStateOf(true) }
    var selectedGolferIndex by remember { mutableStateOf(1) } // 1: Animated Navy, 2: White Polo, 3: Red Polo Male

    var project by remember {
        mutableStateOf(createDemoProject("vertical"))
    }

    val playerController = remember { PlayerController(context) }
    val playerState by playerController.playerState.collectAsState()
    var rawPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    var isIndexing by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf<Int?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    // Touch & Keypoint drag state
    var selectedRoleToSet by remember { mutableStateOf<String?>("start") }
    var touchNormalizedPos by remember { mutableStateOf<Offset?>(null) }
    var isTouching by remember { mutableStateOf(false) }

    val hasContent = rawPlayer != null || isDemoMode
    val currentFrame = if (rawPlayer != null) playerState.currentFrameIndex else demoFrameIndex
    val totalFrames = if (rawPlayer != null) playerState.totalFrames else 120
    val isPlaying = if (rawPlayer != null) playerState.isPlaying else isDemoPlaying

    val activeGolferBitmap: android.graphics.Bitmap? = if (isAnimatedSwingMode && selectedGolferIndex == 1) {
        // Dynamic swing animation driven by current frame!
        when {
            currentFrame < 24 -> swingBitmap1 ?: golferBitmap1
            currentFrame < 46 -> swingBitmap2 ?: golferBitmap1
            currentFrame < 54 -> swingBitmap3 ?: golferBitmap1
            else -> swingBitmap4 ?: golferBitmap1
        }
    } else {
        when (selectedGolferIndex) {
            1 -> golferBitmap1 ?: golferBitmap2 ?: golferBitmap3
            2 -> golferBitmap2 ?: golferBitmap1 ?: golferBitmap3
            3 -> golferBitmap3 ?: golferBitmap1 ?: golferBitmap2
            else -> golferBitmap1
        }
    }

    fun toggleDemoMode() {
        rawPlayer?.pause()
        rawPlayer = null
        isDemoMode = true
        isDemoPlaying = false
        demoOrientation = if (demoOrientation == "vertical") "horizontal" else "vertical"
        project = createDemoProject(demoOrientation)
        demoFrameIndex = 0
    }

    fun resetToDefault() {
        rawPlayer?.pause()
        rawPlayer = null
        isDemoMode = true
        isDemoPlaying = false
        demoOrientation = "vertical"
        project = createDemoProject("vertical")
        demoFrameIndex = 0
        isAutoTracking = false
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isIndexing = true
            isDemoMode = false
            isDemoPlaying = false
            scope.launch {
                try {
                    val metadata = FrameIndexer.indexVideo(context, uri)
                    project = project.copy(
                        video = metadata,
                        trajectories = listOf(
                            project.trajectories.first().copy(
                                keypoints = listOf(
                                    Keypoint("start", 0, 0.44f, 0.76f),
                                    Keypoint("apex", (metadata.frameCount / 2).coerceAtLeast(1), 0.58f, 0.18f),
                                    Keypoint("landing", (metadata.frameCount - 1).coerceAtLeast(2), 0.58f, 0.52f)
                                )
                            )
                        )
                    )
                    playerController.initialize(metadata) { player ->
                        rawPlayer = player
                    }
                } finally {
                    isIndexing = false
                }
            }
        }
    }

    var currentRecordUri by remember { mutableStateOf<Uri?>(null) }
    var currentRecordFile by remember { mutableStateOf<File?>(null) }

    val recordVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success: Boolean ->
        val uri = currentRecordUri
        if (success && uri != null) {
            isIndexing = true
            isDemoMode = false
            isDemoPlaying = false
            scope.launch {
                try {
                    val metadata = FrameIndexer.indexVideo(context, uri)
                    project = project.copy(
                        video = metadata,
                        trajectories = listOf(
                            project.trajectories.first().copy(
                                keypoints = listOf(
                                    Keypoint("start", 0, 0.44f, 0.76f),
                                    Keypoint("apex", (metadata.frameCount / 2).coerceAtLeast(1), 0.58f, 0.18f),
                                    Keypoint("landing", (metadata.frameCount - 1).coerceAtLeast(2), 0.58f, 0.52f)
                                )
                            )
                        )
                    )
                    playerController.initialize(metadata) { player ->
                        rawPlayer = player
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Lỗi đọc video quay: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                } finally {
                    isIndexing = false
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val (file, uri) = com.acetrace.app.core.video.VideoRecordUtil.createVideoRecordFile(context)
                currentRecordFile = file
                currentRecordUri = uri
                recordVideoLauncher.launch(uri)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Lỗi mở camera: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(context, "Cần cấp quyền Camera để quay video", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCameraRecord() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val (file, uri) = com.acetrace.app.core.video.VideoRecordUtil.createVideoRecordFile(context)
                currentRecordFile = file
                currentRecordUri = uri
                recordVideoLauncher.launch(uri)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Lỗi mở camera: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // Video player position update loop
    LaunchedEffect(playerState.isPlaying) {
        while (playerState.isPlaying) {
            rawPlayer?.let { playerController.updateCurrentTime(it.currentPosition) }
            delay(33)
        }
    }

    // Demo mode playback loop
    LaunchedEffect(isDemoPlaying) {
        while (isDemoPlaying) {
            delay(33)
            demoFrameIndex = if (totalFrames > 0) (demoFrameIndex + 1) % totalFrames else 0
        }
    }

    fun startExportPipeline() {
        if (project.video.uri.isNotEmpty() && project.video.uri != "demo") {
            scope.launch {
                val exporter = VideoExporter(context)
                val outFile = File(context.cacheDir, "acetrace_export_${System.currentTimeMillis()}.mp4")
                exporter.exportVideo(project, outFile).collect { state ->
                    when (state) {
                        is ExportState.Progress -> exportProgress = state.percentage
                        is ExportState.Success -> {
                            exportProgress = null
                            exportMessage = "Exported: ${state.outputFile.name}"
                        }
                        is ExportState.Failure -> {
                            exportProgress = null
                            exportMessage = "Error: ${state.error.message}"
                        }
                    }
                }
            }
        } else {
            exportMessage = "Đang xem mẫu Demo Golfer! Hãy chọn nút 'Open' để chọn video thật từ thư viện và xuất file MP4."
        }
    }

    Scaffold(
        containerColor = Color(0xFF0C0C12)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0C0C12))
        ) {
            // Immersive Fullscreen Video Viewport (No bulky header bar squeezing the video)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (hasContent) {
                    if (rawPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = rawPlayer
                                    useController = false
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Pure Overlay Canvas (Draws background golfer + trajectory + hero distance)
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(selectedRoleToSet, project.video) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        isTouching = true
                                        val vRect = computeVideoContentRect(
                                            size.width.toFloat(),
                                            size.height.toFloat(),
                                            project.video.width,
                                            project.video.height,
                                            project.video.rotationDegrees
                                        )
                                        if (vRect.width() <= 0f || vRect.height() <= 0f) return@detectDragGestures
                                        val curTraj = project.trajectories.first()

                                        // Check if user tapped near an existing keypoint handle
                                        val touchedKp = curTraj.keypoints.firstOrNull { kp ->
                                            val kpx = vRect.left + kp.x * vRect.width()
                                            val kpy = vRect.top + kp.y * vRect.height()
                                            kotlin.math.hypot(offset.x - kpx, offset.y - kpy) < 70f
                                        }

                                        val targetRole = touchedKp?.role ?: selectedRoleToSet
                                        if (touchedKp != null) {
                                            selectedRoleToSet = touchedKp.role
                                        }

                                        val normX = ((offset.x - vRect.left) / vRect.width()).coerceIn(0f, 1f)
                                        val normY = ((offset.y - vRect.top) / vRect.height()).coerceIn(0f, 1f)
                                        touchNormalizedPos = Offset(normX, normY)

                                        if (targetRole != null) {
                                            val updatedKeypoints = curTraj.keypoints.toMutableList()
                                            val idx = updatedKeypoints.indexOfFirst { it.role == targetRole }
                                            val existingKp = updatedKeypoints.getOrNull(idx)
                                            val kpFrame = existingKp?.frameIndex ?: currentFrame
                                            val newKp = Keypoint(targetRole, kpFrame, normX, normY)
                                            if (idx >= 0) {
                                                updatedKeypoints[idx] = newKp
                                            } else {
                                                updatedKeypoints.add(newKp)
                                            }
                                            project = project.copy(
                                                trajectories = listOf(curTraj.copy(keypoints = updatedKeypoints))
                                            )
                                        }
                                    },
                                    onDragEnd = { isTouching = false; touchNormalizedPos = null },
                                    onDragCancel = { isTouching = false; touchNormalizedPos = null }
                                ) { change, _ ->
                                    val vRect = computeVideoContentRect(
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                        project.video.width,
                                        project.video.height,
                                        project.video.rotationDegrees
                                    )
                                    val normX = ((change.position.x - vRect.left) / vRect.width()).coerceIn(0f, 1f)
                                    val normY = ((change.position.y - vRect.top) / vRect.height()).coerceIn(0f, 1f)
                                    touchNormalizedPos = Offset(normX, normY)

                                    val role = selectedRoleToSet
                                    if (role != null) {
                                        val curTraj = project.trajectories.first()
                                        val updatedKeypoints = curTraj.keypoints.toMutableList()
                                        val idx = updatedKeypoints.indexOfFirst { it.role == role }
                                        val existingKp = updatedKeypoints.getOrNull(idx)
                                        val kpFrame = existingKp?.frameIndex ?: currentFrame
                                        val newKp = Keypoint(role, kpFrame, normX, normY)
                                        if (idx >= 0) {
                                            updatedKeypoints[idx] = newKp
                                        } else {
                                            updatedKeypoints.add(newKp)
                                        }
                                        project = project.copy(
                                            trajectories = listOf(curTraj.copy(keypoints = updatedKeypoints))
                                        )
                                    }
                                }
                            }
                    ) {
                        val vRect = computeVideoContentRect(
                            size.width,
                            size.height,
                            project.video.width,
                            project.video.height,
                            project.video.rotationDegrees
                        )

                        drawIntoCanvas { canvas ->
                            // 1. Render Golfer Background Photo in Demo Mode
                            if (rawPlayer == null && isDemoMode) {
                                val activeBmp = if (demoOrientation == "vertical") activeGolferBitmap else demoHorizontalBitmap
                                if (activeBmp != null) {
                                    val srcRect = Rect(0, 0, activeBmp.width, activeBmp.height)
                                    val dstRect = Rect(
                                        vRect.left.toInt(),
                                        vRect.top.toInt(),
                                        vRect.right.toInt(),
                                        vRect.bottom.toInt()
                                    )
                                    val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                                    canvas.nativeCanvas.drawBitmap(activeBmp, srcRect, dstRect, bmpPaint)
                                } else {
                                    val bgPaint = Paint().apply {
                                        color = AndroidColor.parseColor("#152218")
                                        style = Paint.Style.FILL
                                    }
                                    canvas.nativeCanvas.drawRect(vRect, bgPaint)
                                }
                            }

                            // 2. Render Trajectory, Glowing Line, Wave and Hero Distance Overlays
                            OverlayRenderer.render(
                                frameIndex = currentFrame,
                                project = project,
                                canvas = canvas.nativeCanvas,
                                canvasWidth = size.width,
                                canvasHeight = size.height,
                                contentRect = vRect
                            )

                            // 3. Auto-Tracking Yellow Corner Brackets [  ] (matching reference Screenshot 2A)
                            if (isAutoTracking) {
                                val curTraj = project.trajectories.firstOrNull()
                                val pts = curTraj?.keypoints ?: emptyList()
                                if (pts.isNotEmpty()) {
                                    val flightState = com.acetrace.app.core.curve.TimeMapping.computeFlightStateFromKeypoints(currentFrame, pts)
                                    val path = com.acetrace.app.core.curve.BezierPath.fromKeypoints(pts)
                                    val ballPt = path.point(flightState.u)
                                    val bx = vRect.left + ballPt.x * vRect.width()
                                    val by = vRect.top + ballPt.y * vRect.height()

                                    val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = AndroidColor.parseColor("#FFD600")
                                        style = Paint.Style.STROKE
                                        strokeWidth = 4f
                                    }
                                    val bSize = 36f
                                    val arm = 14f

                                    // Top-left
                                    canvas.nativeCanvas.drawLine(bx - bSize, by - bSize, bx - bSize + arm, by - bSize, bracketPaint)
                                    canvas.nativeCanvas.drawLine(bx - bSize, by - bSize, bx - bSize, by - bSize + arm, bracketPaint)
                                    // Top-right
                                    canvas.nativeCanvas.drawLine(bx + bSize, by - bSize, bx + bSize - arm, by - bSize, bracketPaint)
                                    canvas.nativeCanvas.drawLine(bx + bSize, by - bSize, bx + bSize - arm, by - bSize, bracketPaint)
                                    // Bottom-left
                                    canvas.nativeCanvas.drawLine(bx - bSize, by + bSize, bx - bSize + arm, by + bSize, bracketPaint)
                                    canvas.nativeCanvas.drawLine(bx - bSize, by + bSize, bx - bSize, by + bSize - arm, bracketPaint)
                                    // Bottom-right
                                    canvas.nativeCanvas.drawLine(bx + bSize, by + bSize, bx + bSize - arm, by + bSize, bracketPaint)
                                    canvas.nativeCanvas.drawLine(bx + bSize, by + bSize, bx + bSize, by + bSize - arm, bracketPaint)
                                }
                            }

                            // 4. Render Minimalist Glowing Pin Handles in Paused Mode (No cluttering text badges over golfer!)
                            if (!isPlaying) {
                                val currentTraj = project.trajectories.firstOrNull()
                                currentTraj?.keypoints?.forEach { kp ->
                                    val px = vRect.left + kp.x * vRect.width()
                                    val py = vRect.top + kp.y * vRect.height()
                                    val isSelected = selectedRoleToSet == kp.role
                                    val handleColor = when (kp.role) {
                                        "start" -> AndroidColor.parseColor("#00FF66")
                                        "apex" -> AndroidColor.parseColor("#00E5FF")
                                        "landing" -> AndroidColor.parseColor("#FF2D95")
                                        else -> AndroidColor.WHITE
                                    }

                                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = handleColor
                                        style = Paint.Style.FILL
                                    }
                                    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = if (isSelected) AndroidColor.WHITE else AndroidColor.parseColor("#444455")
                                        style = Paint.Style.STROKE
                                        strokeWidth = if (isSelected) 3.5f else 2f
                                    }
                                    val radius = if (isSelected) 12f else 9f
                                    canvas.nativeCanvas.drawCircle(px, py, radius, fillPaint)
                                    canvas.nativeCanvas.drawCircle(px, py, radius, strokePaint)

                                    // Only show text badge when user is actively dragging/touching that handle!
                                    if (isTouching && isSelected) {
                                        val labelText = kp.role.uppercase()
                                        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            color = AndroidColor.WHITE
                                            textSize = 18f
                                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                                            textAlign = Paint.Align.CENTER
                                            setShadowLayer(4f, 0f, 2f, AndroidColor.BLACK)
                                        }
                                        val labelY = if (kp.role == "start") py + radius + 18f else py - radius - 8f
                                        canvas.nativeCanvas.drawText(labelText, px, labelY, labelPaint)
                                    }
                                }
                            }
                        }
                    }

                    // Ultra-Compact Sleek Top Bar (Never overflows or wraps vertically)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // (X) Button (Circle)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                .clickable { resetToDefault() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Compact Toolbar Capsule
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color.Black.copy(alpha = 0.60f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Animated Motion toggle: [🎬 Swing]
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isAnimatedSwingMode) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.15f))
                                        .clickable { isAnimatedSwingMode = !isAnimatedSwingMode }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isAnimatedSwingMode) "🎬 Swing" else "📷 Ảnh",
                                        color = if (isAnimatedSwingMode) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                // 3 Quick Golfer Pickers: [1] [2] [3]
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    listOf(1, 2, 3).forEach { idx ->
                                        val isSel = selectedGolferIndex == idx
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(if (isSel) Color(0xFFFF2D95) else Color.White.copy(alpha = 0.18f))
                                                .clickable {
                                                    selectedGolferIndex = idx
                                                    if (idx != 1) isAnimatedSwingMode = false
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = idx.toString(),
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(12.dp)
                                        .background(Color.White.copy(alpha = 0.25f))
                                )

                                // Record Video Directly from Camera
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFFF3366).copy(alpha = 0.9f))
                                        .clickable { launchCameraRecord() }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "📹 Quay",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                // Open Button
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.12f))
                                        .clickable { videoPickerLauncher.launch("video/*") }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Mở",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                // Spike Button
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFFF4081).copy(alpha = 0.35f))
                                        .clickable {
                                            context.startActivity(Intent(context, com.acetrace.spike.SpikeActivity::class.java))
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Spike",
                                        color = Color(0xFFFF4081),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    // Magnifier viewfinder during touch
                    if (isTouching && touchNormalizedPos != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 50.dp)
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(Color(0xEE12121A))
                                .border(2.dp, Color(0xFF00E5FF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    selectedRoleToSet?.uppercase() ?: "POINT",
                                    color = when (selectedRoleToSet) {
                                        "start" -> Color(0xFF00FF66)
                                        "apex" -> Color(0xFF00E5FF)
                                        else -> Color(0xFFFF2D95)
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "X: ${((touchNormalizedPos?.x ?: 0f) * 100).toInt()}%  Y: ${((touchNormalizedPos?.y ?: 0f) * 100).toInt()}%",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                if (isIndexing) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF))
                }
            }

            // Floating Mode Pill centered directly above bottom deck (matching Screenshot 1: "Line style")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C0C12))
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF181824),
                    border = BorderStroke(1.dp, Color(0xFF2E2E40))
                ) {
                    Text(
                        text = when (activeTab) {
                            "style" -> "Line style"
                            "settings" -> "Settings"
                            "text" -> "Distance: ${project.trajectories.first().distance.value.toInt()}${project.trajectories.first().distance.unit}"
                            "effects" -> if (isAutoTracking) "Tracking in progress..." else "Wave"
                            else -> "Render"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
            }

            // Bottom Tool Panel (Clean layout strictly matching reference Screenshots 1 & 2)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF12121A))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Tab 1: STYLE VIEW (Matching Screenshot 1)
                if (activeTab == "style") {
                    // Row 1: Mode Toggles ([ 3D ] & ][) centered on left, and 8 curated color swatches
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 3D Cube & 2D Curve Icons (matching Screenshot 1)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ViewInAr,
                                contentDescription = "3D Mode",
                                tint = if (project.trajectories.first().style.is3D) Color(0xFF00E5FF) else Color.Gray,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable {
                                        val curr = project.trajectories.first()
                                        project = project.copy(
                                            trajectories = listOf(curr.copy(style = curr.style.copy(is3D = !curr.style.is3D)))
                                        )
                                    }
                            )
                            Icon(
                                Icons.Default.CropLandscape,
                                contentDescription = "2D Mode",
                                tint = if (!project.trajectories.first().style.is3D) Color(0xFF00E5FF) else Color.Gray,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable {
                                        val curr = project.trajectories.first()
                                        project = project.copy(
                                            trajectories = listOf(curr.copy(style = curr.style.copy(is3D = false)))
                                        )
                                    }
                            )
                        }

                        // 8 Curated Color Swatches (matching Screenshot 1: Coral, Peach, Cyan, Aurora, Green, Sky, Yellow, White)
                        val swatches = listOf(
                            "coral" to listOf("#FF2D55", "#FF5588"),
                            "peach" to listOf("#FF7755", "#FFAA77"),
                            "cyan" to listOf("#00E5FF", "#0088FF"),
                            "aurora" to listOf("#00E5FF", "#FF2D95", "#FFB300"),
                            "green" to listOf("#00FF66", "#00E5FF"),
                            "sky" to listOf("#0099FF", "#00E5FF"),
                            "yellow" to listOf("#FFE500", "#FF8800"),
                            "white" to listOf("#FFFFFF", "#AACCFF")
                        )

                        val curPalette = project.trajectories.first().style.palette
                        swatches.forEach { (name, grad) ->
                            val isSel = curPalette == name
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            colors = grad.map { Color(android.graphics.Color.parseColor(it)) }
                                        )
                                    )
                                    .border(
                                        width = if (isSel) 2.5.dp else 1.dp,
                                        color = if (isSel) Color.White else Color.Black.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        val curr = project.trajectories.first()
                                        project = project.copy(
                                            trajectories = listOf(
                                                curr.copy(
                                                    style = curr.style.copy(
                                                        palette = name,
                                                        gradient = grad
                                                    )
                                                )
                                            )
                                        )
                                    }
                            )
                        }
                    }

                    // Row 2: Secondary subtle options (Tracer / Full / Comet)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("tracer" to "Tracer", "full" to "Full", "comet" to "Comet").forEach { (mode, label) ->
                            val isSelected = project.trajectories.first().style.trailMode == mode
                            Surface(
                                onClick = {
                                    val curr = project.trajectories.first()
                                    project = project.copy(
                                        trajectories = listOf(curr.copy(style = curr.style.copy(trailMode = mode)))
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.18f) else Color(0xFF1E1E2C),
                                border = if (isSelected) BorderStroke(1.dp, Color(0xFF00E5FF)) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) Color(0xFF00E5FF) else Color.LightGray
                                    )
                                }
                            }
                        }

                        // Handles selector: Start / Apex / Landing
                        listOf("start" to "Start", "apex" to "Apex", "landing" to "Landing").forEach { (role, label) ->
                            val isSel = selectedRoleToSet == role
                            val rColor = when (role) {
                                "start" -> Color(0xFF00FF66)
                                "apex" -> Color(0xFF00E5FF)
                                else -> Color(0xFFFF2D95)
                            }
                            Surface(
                                onClick = { selectedRoleToSet = role },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) rColor else Color(0xFF1E1E2C),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // Tab 2: SETTINGS VIEW
                if (activeTab == "settings") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Units: ft / yd / m
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("ft", "yd", "m").forEach { u ->
                                val curUnit = project.trajectories.first().distance.unit
                                val isSel = curUnit == u
                                Surface(
                                    onClick = {
                                        val curr = project.trajectories.first()
                                        project = project.copy(
                                            trajectories = listOf(curr.copy(distance = curr.distance.copy(unit = u)))
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) Color(0xFF00E5FF) else Color(0xFF1E1E2C),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                        Text(u, fontWeight = FontWeight.Bold, color = if (isSel) Color.Black else Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        // Distance value adjustments
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(285f, 350f, 450f, 500f).forEach { dVal ->
                                val curVal = project.trajectories.first().distance.value
                                val isSel = curVal == dVal
                                Surface(
                                    onClick = {
                                        val curr = project.trajectories.first()
                                        project = project.copy(
                                            trajectories = listOf(curr.copy(distance = curr.distance.copy(value = dVal)))
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) Color(0xFFFF2D95) else Color(0xFF1E1E2C),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                        Text("${dVal.toInt()}", fontWeight = FontWeight.Bold, color = if (isSel) Color.White else Color.LightGray, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Tab 3: TEXT & WATERMARK VIEW
                if (activeTab == "text") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val currTraj = project.trajectories.first()

                        Surface(
                            onClick = {
                                val curr = project.trajectories.first()
                                project = project.copy(
                                    trajectories = listOf(curr.copy(distance = curr.distance.copy(showHeroOverlay = !curr.distance.showHeroOverlay)))
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (currTraj.distance.showHeroOverlay) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E1E2C),
                            border = if (currTraj.distance.showHeroOverlay) BorderStroke(1.dp, Color(0xFF00E5FF)) else null,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text(
                                    if (currTraj.distance.showHeroOverlay) "✓ Hero ${currTraj.distance.value.toInt()}${currTraj.distance.unit}" else "Hero Overlay",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currTraj.distance.showHeroOverlay) Color(0xFF00E5FF) else Color.Gray
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                project = project.copy(export = project.export.copy(watermark = !project.export.watermark))
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (project.export.watermark) Color(0xFF1E1E2C) else Color(0xFF14141E),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text(
                                    if (project.export.watermark) "Watermark: ON" else "Watermark: OFF",
                                    fontSize = 11.sp,
                                    color = if (project.export.watermark) Color.White else Color.Gray
                                )
                            }
                        }
                    }
                }

                // Tab 4: EFFECTS VIEW (Matching Screenshot 2A & 2C: Wave, Sparkle, Auto-track)
                if (activeTab == "effects") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val currTraj = project.trajectories.first()

                        Surface(
                            onClick = {
                                val newEffect = if (currTraj.style.effectMode == "wave") "standard" else "wave"
                                project = project.copy(
                                    trajectories = listOf(currTraj.copy(style = currTraj.style.copy(effectMode = newEffect)))
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (currTraj.style.effectMode == "wave") Color(0xFFFF2D95).copy(alpha = 0.25f) else Color(0xFF1E1E2C),
                            border = if (currTraj.style.effectMode == "wave") BorderStroke(1.dp, Color(0xFFFF2D95)) else null,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text(
                                    "Wave Effect",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currTraj.style.effectMode == "wave") Color(0xFFFF2D95) else Color.LightGray
                                )
                            }
                        }

                        Surface(
                            onClick = { isAutoTracking = !isAutoTracking },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isAutoTracking) Color(0xFFFFD600).copy(alpha = 0.25f) else Color(0xFF1E1E2C),
                            border = if (isAutoTracking) BorderStroke(1.dp, Color(0xFFFFD600)) else null,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text(
                                    if (isAutoTracking) "[ Auto-Tracking ]" else "[ ] Auto-Track",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAutoTracking) Color(0xFFFFD600) else Color.LightGray
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                project = project.copy(
                                    trajectories = listOf(currTraj.copy(style = currTraj.style.copy(showImpactFlash = !currTraj.style.showImpactFlash)))
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (currTraj.style.showImpactFlash) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E1E2C),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text(
                                    "Impact Flash",
                                    fontSize = 11.sp,
                                    color = if (currTraj.style.showImpactFlash) Color(0xFF00E5FF) else Color.Gray
                                )
                            }
                        }
                    }
                }

                // Tab 5: RENDER VIEW
                if (activeTab == "render") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Export High-Res MP4 Video",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )

                        Button(
                            onClick = { startExportPipeline() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Slim Transport Scrubber with Play/Pause button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${currentFrame} / ${totalFrames.coerceAtLeast(1)}",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (rawPlayer != null) playerController.step(-1)
                                else demoFrameIndex = (demoFrameIndex - 1).coerceAtLeast(0)
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Step -1", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = {
                                if (rawPlayer != null) playerController.togglePlay()
                                else isDemoPlaying = !isDemoPlaying
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Play/Pause",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                if (rawPlayer != null) playerController.step(1)
                                else demoFrameIndex = (demoFrameIndex + 1).coerceAtMost(totalFrames - 1)
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, "Step +1", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (totalFrames > 1) {
                    Slider(
                        value = currentFrame.toFloat().coerceIn(0f, (totalFrames - 1).toFloat()),
                        onValueChange = { frame ->
                            if (rawPlayer != null) playerController.seekToFrame(frame.toInt())
                            else demoFrameIndex = frame.toInt()
                        },
                        valueRange = 0f..(totalFrames - 1).toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = Color(0xFF222232)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                    )
                }
            }

            // Bottom Navigation Bar (Matching Screenshot 1 & 2: Back | Style | Settings | Text | Effects | Render)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C0C12))
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { resetToDefault() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Back", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                }

                // Style Tab
                BottomTabItem(
                    title = "Style",
                    icon = Icons.Default.Brush,
                    isSelected = activeTab == "style",
                    onClick = { activeTab = "style" }
                )

                // Settings Tab
                BottomTabItem(
                    title = "Settings",
                    icon = Icons.Default.Tune,
                    isSelected = activeTab == "settings",
                    onClick = { activeTab = "settings" }
                )

                // Text Tab
                BottomTabItem(
                    title = "Text",
                    icon = Icons.Default.TextFields,
                    isSelected = activeTab == "text",
                    onClick = { activeTab = "text" }
                )

                // Effects Tab
                BottomTabItem(
                    title = "Effects",
                    icon = Icons.Default.AutoAwesome,
                    isSelected = activeTab == "effects",
                    onClick = { activeTab = "effects" }
                )

                // Render Tab
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable {
                            activeTab = "render"
                            startExportPipeline()
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "Render",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                }
            }
        }
    }

    // Export Progress Dialog
    if (exportProgress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting Video...", color = Color.White) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(
                        progress = { (exportProgress ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("${exportProgress}%", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {},
            containerColor = Color(0xFF1E1E2C)
        )
    }

    // Export Result Message Dialog
    exportMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { exportMessage = null },
            title = { Text("AceTrace Thông Báo", color = Color.White) },
            text = { Text(msg, color = Color.LightGray) },
            confirmButton = {
                Button(onClick = { exportMessage = null }) {
                    Text("OK")
                }
            },
            containerColor = Color(0xFF1E1E2C)
        )
    }
}

@Composable
fun BottomTabItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (isSelected) Color(0xFF00E5FF) else Color.Gray,
            modifier = Modifier.size(19.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color(0xFF00E5FF) else Color.Gray
        )
    }
}
