package com.acetrace.app

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFFFF2D95),
                    background = Color(0xFF0F0F14),
                    surface = Color(0xFF181822)
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

    var project by remember {
        mutableStateOf(
            Project(
                id = UUID.randomUUID().toString(),
                createdAt = "2026-10-05T00:00:00Z",
                video = VideoMetadata(uri = "", width = 1080, height = 1920),
                trajectories = listOf(
                    Trajectory(
                        id = UUID.randomUUID().toString(),
                        keypoints = emptyList(),
                        style = TrajectoryStyle(
                            palette = "neonCyan",
                            gradient = listOf("#00E5FF", "#FF2D95", "#FFB300"),
                            lineWidth = 8f,
                            glow = 0.8f,
                            trailMode = "tracer"
                        )
                    )
                )
            )
        )
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

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isIndexing = true
            scope.launch {
                try {
                    val metadata = FrameIndexer.indexVideo(context, uri)
                    project = project.copy(
                        video = metadata,
                        trajectories = listOf(
                            project.trajectories.first().copy(
                                keypoints = listOf(
                                    Keypoint("start", 0, 0.5f, 0.8f),
                                    Keypoint("apex", (metadata.frameCount / 2).coerceAtLeast(1), 0.5f, 0.3f),
                                    Keypoint("landing", (metadata.frameCount - 1).coerceAtLeast(2), 0.5f, 0.7f)
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

    // Periodic position polling for timeline synchronization
    LaunchedEffect(playerState.isPlaying) {
        while (playerState.isPlaying) {
            rawPlayer?.let { playerController.updateCurrentTime(it.currentPosition) }
            delay(33) // ~30 fps update rate
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14141E))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AceTrace Shot Tracker",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { videoPickerLauncher.launch("video/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242436))
                    ) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = "Open")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open")
                    }

                    Button(
                        onClick = {
                            if (project.video.uri.isNotEmpty()) {
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
                            }
                        },
                        enabled = project.video.uri.isNotEmpty() && exportProgress == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Export", tint = Color.Black)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0F0F14))
        ) {
            // Video Player + Overlay Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
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

                    // Overlay Canvas using OverlayRenderer
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(selectedRoleToSet) {
                                detectTapGestures { offset ->
                                    val normX = offset.x / size.width
                                    val normY = offset.y / size.height
                                    val role = selectedRoleToSet ?: return@detectTapGestures

                                    // Update keypoint
                                    val currentTraj = project.trajectories.first()
                                    val updatedKeypoints = currentTraj.keypoints.toMutableList()
                                    val idx = updatedKeypoints.indexOfFirst { it.role == role }
                                    val newKp = Keypoint(role, playerState.currentFrameIndex, normX, normY)
                                    if (idx >= 0) {
                                        updatedKeypoints[idx] = newKp
                                    } else {
                                        updatedKeypoints.add(newKp)
                                    }
                                    project = project.copy(
                                        trajectories = listOf(currentTraj.copy(keypoints = updatedKeypoints))
                                    )
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        isTouching = true
                                        touchNormalizedPos = Offset(offset.x / size.width, offset.y / size.height)
                                    },
                                    onDragEnd = { isTouching = false; touchNormalizedPos = null },
                                    onDragCancel = { isTouching = false; touchNormalizedPos = null }
                                ) { change, _ ->
                                    val normX = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val normY = (change.position.y / size.height).coerceIn(0f, 1f)
                                    touchNormalizedPos = Offset(normX, normY)

                                    val role = selectedRoleToSet
                                    if (role != null) {
                                        val currentTraj = project.trajectories.first()
                                        val updatedKeypoints = currentTraj.keypoints.toMutableList()
                                        val idx = updatedKeypoints.indexOfFirst { it.role == role }
                                        val newKp = Keypoint(role, playerState.currentFrameIndex, normX, normY)
                                        if (idx >= 0) {
                                            updatedKeypoints[idx] = newKp
                                        } else {
                                            updatedKeypoints.add(newKp)
                                        }
                                        project = project.copy(
                                            trajectories = listOf(currentTraj.copy(keypoints = updatedKeypoints))
                                        )
                                    }
                                }
                            }
                    ) {
                        drawIntoCanvas { canvas ->
                            OverlayRenderer.render(
                                frameIndex = playerState.currentFrameIndex,
                                project = project,
                                canvas = canvas.nativeCanvas,
                                canvasWidth = size.width,
                                canvasHeight = size.height
                            )
                        }
                    }

                    // Viewfinder / Magnifier
                    if (isTouching && touchNormalizedPos != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp)
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(Color(0xEE1E1E2C)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "(${((touchNormalizedPos?.x ?: 0f) * 100).toInt()}%, ${((touchNormalizedPos?.y ?: 0f) * 100).toInt()}%)",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Movie,
                            contentDescription = "No video",
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Open a sports video to start tracing", color = Color.Gray)
                    }
                }

                if (isIndexing) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF))
                }
            }

            // Bottom Control Deck (Timeline Scrubber + Keypoint Markers + Style Controls)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14141E))
                    .padding(12.dp)
            ) {
                // Frame Counter & Timecode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Frame: ${playerState.currentFrameIndex} / ${playerState.totalFrames}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { playerController.step(-1) }) {
                            Icon(Icons.Default.SkipPrevious, "Step -1", tint = Color.White)
                        }
                        IconButton(onClick = { playerController.togglePlay() }) {
                            Icon(
                                if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Play/Pause",
                                tint = Color(0xFF00E5FF)
                            )
                        }
                        IconButton(onClick = { playerController.step(1) }) {
                            Icon(Icons.Default.SkipNext, "Step +1", tint = Color.White)
                        }
                    }
                }

                // Scrubber Slider
                if (playerState.totalFrames > 1) {
                    Slider(
                        value = playerState.currentFrameIndex.toFloat(),
                        onValueChange = { frame ->
                            playerController.seekToFrame(frame.toInt())
                        },
                        valueRange = 0f..(playerState.totalFrames - 1).toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Keypoint placement buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val roles = listOf("start" to "Start", "apex" to "Apex", "landing" to "Landing")
                    roles.forEach { (role, label) ->
                        val isSelected = selectedRoleToSet == role
                        Button(
                            onClick = { selectedRoleToSet = role },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF242436),
                                contentColor = if (isSelected) Color.Black else Color.White
                            )
                        ) {
                            Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                // Trail Mode selector (Tracer / Full / Comet)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modes = listOf("tracer" to "Tracer", "full" to "Full", "comet" to "Comet")
                    modes.forEach { (mode, label) ->
                        val currentMode = project.trajectories.first().style.trailMode
                        val isSelected = currentMode == mode
                        OutlinedButton(
                            onClick = {
                                val curr = project.trajectories.first()
                                project = project.copy(
                                    trajectories = listOf(curr.copy(style = curr.style.copy(trailMode = mode)))
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isSelected) Color(0xFF00E5FF) else Color.Gray
                            )
                        ) {
                            Text(label, fontSize = 11.sp)
                        }
                    }
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
            title = { Text("Export Notice", color = Color.White) },
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
