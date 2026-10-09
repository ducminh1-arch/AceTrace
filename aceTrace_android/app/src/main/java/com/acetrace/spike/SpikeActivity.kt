package com.acetrace.spike

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.random.Random

@OptIn(UnstableApi::class)
class SpikeActivity : ComponentActivity() {

    private var exactPlayer: ExactPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exactPlayer = ExactPlayer(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFFFF4081)
                )
            ) {
                SpikeScreen(
                    exactPlayer = exactPlayer!!,
                    onOpenVideo = { file -> openVideoFile(file) },
                    onShareFile = { file, mime -> shareFile(file, mime) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exactPlayer?.release()
        exactPlayer = null
    }

    private fun openVideoFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể mở video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Chia sẻ tệp"))
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể chia sẻ tệp: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

// Data structures for Seek Test Results
data class SeekTestRecord(
    val seekIndex: Int,
    val targetFrame: Int,
    val targetPtsUs: Long,
    val renderedPtsUs: Long,
    val deltaUs: Long,
    val latencyMs: Long,
    val isSuccess: Boolean
)

data class SeekTestSummary(
    val totalSeeks: Int = 100,
    val successCount: Int = 0,
    val detectedOffsetUs: Long = 0L,
    val medianLatencyMs: Long = 0L,
    val p95LatencyMs: Long = 0L,
    val maxLatencyMs: Long = 0L,
    val csvFilePath: String = "",
    val csvContent: String = ""
)

data class StepDriftSummary(
    val stepsTaken: Int = 0,
    val initialPtsUs: Long = 0L,
    val finalPtsUs: Long = 0L,
    val driftUs: Long = 0L,
    val isPassed: Boolean = false
)

@OptIn(UnstableApi::class)
@Composable
fun SpikeScreen(
    exactPlayer: ExactPlayer,
    onOpenVideo: (File) -> Unit,
    onShareFile: (File, String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var selectedTab by remember { mutableStateOf(0) }

    // Video state
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var frameIndex by remember { mutableStateOf<FrameIndex?>(null) }
    var indexBuildTimeMs by remember { mutableStateOf<Long?>(null) }
    var isIndexing by remember { mutableStateOf(false) }

    // Playback state
    var currentFrame by remember { mutableStateOf(0) }
    var targetPtsUs by remember { mutableStateOf(0L) }
    var lastRenderedPtsUs by remember { mutableStateOf(-1L) }
    var renderedCount by remember { mutableStateOf(0) }
    var frameInputText by remember { mutableStateOf("0") }

    // Test states
    var isRunningSeekTest by remember { mutableStateOf(false) }
    var seekTestProgress by remember { mutableStateOf(0) }
    var seekTestSummary by remember { mutableStateOf<SeekTestSummary?>(null) }

    var isRunningDriftTest by remember { mutableStateOf(false) }
    var driftTestStatus by remember { mutableStateOf("") }
    var stepDriftSummary by remember { mutableStateOf<StepDriftSummary?>(null) }

    // Export states
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0) }
    var exportTimeSec by remember { mutableStateOf(0L) }
    var exportFileSizeMb by remember { mutableStateOf(0.0) }
    var exportedFile by remember { mutableStateOf<File?>(null) }
    var exportStatusText by remember { mutableStateOf("") }

    fun loadAndIndexVideo(uri: Uri) {
        videoUri = uri
        isIndexing = true
        seekTestSummary = null
        stepDriftSummary = null
        coroutineScope.launch(Dispatchers.IO) {
            val start = SystemClock.uptimeMillis()
            try {
                val (info, index) = FrameIndexBuilder.build(context, uri)
                val elapsed = SystemClock.uptimeMillis() - start
                withContext(Dispatchers.Main) {
                    videoInfo = info
                    frameIndex = index
                    indexBuildTimeMs = elapsed
                    isIndexing = false
                    currentFrame = 0
                    frameInputText = "0"
                    exactPlayer.setMediaUri(uri)
                    exactPlayer.seekToFrame(index, 0)
                    targetPtsUs = if (index.count > 0) index.ptsUs[0] else 0L
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isIndexing = false
                    Toast.makeText(context, "Lỗi đọc video: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Document Picker
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadAndIndexVideo(uri)
        }
    }

    var pendingRecordUri by remember { mutableStateOf<Uri?>(null) }

    val recordVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success: Boolean ->
        val uri = pendingRecordUri
        if (success && uri != null) {
            loadAndIndexVideo(uri)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val (_, uri) = com.acetrace.app.core.video.VideoRecordUtil.createVideoRecordFile(context)
                pendingRecordUri = uri
                recordVideoLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "Lỗi mở camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Cần cấp quyền Camera để quay video", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCameraRecord() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                val (_, uri) = com.acetrace.app.core.video.VideoRecordUtil.createVideoRecordFile(context)
                pendingRecordUri = uri
                recordVideoLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "Lỗi mở camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Polling player status for live UI
    LaunchedEffect(exactPlayer) {
        while (true) {
            lastRenderedPtsUs = exactPlayer.lastRenderedPtsUs
            renderedCount = exactPlayer.renderedCount
            delay(100)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // App Header & Device Info
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AceTrace Spike — Phase 0",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, com.acetrace.app.MainActivity::class.java)
                            context.startActivity(intent)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Về AceTrace", fontSize = 11.sp, color = Color(0xFF00E5FF))
                    }
                }
                val totalRamMb = getTotalRamMb(context)
                Text(
                    text = "Thiết bị: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | RAM: ${totalRamMb}MB | Media3: 1.3.1",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
            }
        }

        // 2 Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Tab A: Frame Test (Tua)") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Tab B: Export Test (Xuất)") }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                // TAB A: FRAME TEST
                TabAFrameTest(
                    videoUri = videoUri,
                    videoInfo = videoInfo,
                    frameIndex = frameIndex,
                    indexBuildTimeMs = indexBuildTimeMs,
                    isIndexing = isIndexing,
                    exactPlayer = exactPlayer,
                    currentFrame = currentFrame,
                    targetPtsUs = targetPtsUs,
                    lastRenderedPtsUs = lastRenderedPtsUs,
                    renderedCount = renderedCount,
                    frameInputText = frameInputText,
                    onFrameInputChange = { frameInputText = it },
                    onSelectVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                    onRecordVideo = { launchCameraRecord() },
                    onStepFrame = { delta ->
                        val index = frameIndex ?: return@TabAFrameTest
                        val next = (currentFrame + delta).coerceIn(0, index.count - 1)
                        currentFrame = next
                        frameInputText = next.toString()
                        targetPtsUs = index.ptsUs[next]
                        exactPlayer.seekToFrame(index, next)
                    },
                    onGoFrame = {
                        val index = frameIndex ?: return@TabAFrameTest
                        val target = frameInputText.toIntOrNull()?.coerceIn(0, index.count - 1) ?: currentFrame
                        currentFrame = target
                        targetPtsUs = index.ptsUs[target]
                        exactPlayer.seekToFrame(index, target)
                    },
                    isRunningSeekTest = isRunningSeekTest,
                    seekTestProgress = seekTestProgress,
                    seekTestSummary = seekTestSummary,
                    onRunSeekTest = {
                        val index = frameIndex ?: return@TabAFrameTest
                        isRunningSeekTest = true
                        seekTestProgress = 0
                        seekTestSummary = null
                        coroutineScope.launch(Dispatchers.Default) {
                            val summary = run100RandomSeeks(context, exactPlayer, index) { prog ->
                                seekTestProgress = prog
                            }
                            withContext(Dispatchers.Main) {
                                seekTestSummary = summary
                                isRunningSeekTest = false
                            }
                        }
                    },
                    isRunningDriftTest = isRunningDriftTest,
                    driftTestStatus = driftTestStatus,
                    stepDriftSummary = stepDriftSummary,
                    onRunDriftTest = {
                        val index = frameIndex ?: return@TabAFrameTest
                        isRunningDriftTest = true
                        driftTestStatus = "Đang bắt đầu bước drift test..."
                        stepDriftSummary = null
                        coroutineScope.launch(Dispatchers.Default) {
                            val result = runStepDriftTest(exactPlayer, index) { status ->
                                driftTestStatus = status
                            }
                            withContext(Dispatchers.Main) {
                                stepDriftSummary = result
                                isRunningDriftTest = false
                                driftTestStatus = if (result.isPassed) "Xong: ĐẠT (Drift = 0)" else "Xong: KHÔNG ĐẠT (Lệch ${result.driftUs}us)"
                            }
                        }
                    },
                    onCopyCsv = { csv ->
                        clipboardManager.setText(AnnotatedString(csv))
                        Toast.makeText(context, "Đã sao chép CSV vào bộ nhớ tạm", Toast.LENGTH_SHORT).show()
                    },
                    onShareCsv = { filePath ->
                        val file = File(filePath)
                        if (file.exists()) {
                            onShareFile(file, "text/csv")
                        }
                    }
                )
            } else {
                // TAB B: EXPORT TEST
                TabBExportTest(
                    videoUri = videoUri,
                    videoInfo = videoInfo,
                    frameIndex = frameIndex,
                    isIndexing = isIndexing,
                    onSelectVideo = { videoPickerLauncher.launch(arrayOf("video/*")) },
                    onRecordVideo = { launchCameraRecord() },
                    isExporting = isExporting,
                    exportProgress = exportProgress,
                    exportTimeSec = exportTimeSec,
                    exportFileSizeMb = exportFileSizeMb,
                    exportedFile = exportedFile,
                    exportStatusText = exportStatusText,
                    onStartExport = {
                        val uri = videoUri ?: return@TabBExportTest
                        val index = frameIndex ?: return@TabBExportTest
                        val info = videoInfo ?: return@TabBExportTest

                        isExporting = true
                        exportProgress = 0
                        exportTimeSec = 0L
                        exportFileSizeMb = 0.0
                        exportedFile = null
                        exportStatusText = "Bắt đầu xuất video..."

                        coroutineScope.launch(Dispatchers.Main) {
                            val exportRunner = ExportRunner(context)
                            val startTime = SystemClock.uptimeMillis()

                            // Output path: Movies/AceTraceSpike/spike_export_<timestamp>.mp4
                            val outDir = getOutputDir(context)
                            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val outFile = File(outDir, "spike_export_$timeStamp.mp4")

                            // Timer counter
                            val timerJob = launch {
                                while (isActive) {
                                    exportTimeSec = (SystemClock.uptimeMillis() - startTime) / 1000L
                                    delay(500)
                                }
                            }

                            val overlay = SpikeOverlay(
                                displayW = info.displayWidth,
                                displayH = info.displayHeight,
                                index = index,
                                firstPtsUs = if (index.count > 0) index.ptsUs[0] else 0L
                            )

                            exportRunner.runExport(
                                inputUri = uri,
                                outFile = outFile,
                                overlay = overlay,
                                onProgress = { p ->
                                    exportProgress = p
                                    exportStatusText = "Đang xuất: $p%"
                                },
                                onDone = { result ->
                                    timerJob.cancel()
                                    val totalSec = (SystemClock.uptimeMillis() - startTime) / 1000L
                                    exportTimeSec = totalSec
                                    isExporting = false
                                    exportedFile = outFile
                                    exportFileSizeMb = (outFile.length().toDouble() / (1024.0 * 1024.0))
                                    exportStatusText = "Hoàn tất! Thời gian: ${totalSec}s | Size: ${String.format(Locale.US, "%.2f", exportFileSizeMb)} MB"
                                    Toast.makeText(context, "Export thành công!", Toast.LENGTH_LONG).show()
                                },
                                onError = { ex ->
                                    timerJob.cancel()
                                    isExporting = false
                                    exportStatusText = "Lỗi xuất: ${ex.message}"
                                    Toast.makeText(context, "Lỗi: ${ex.message}", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    onOpenVideo = onOpenVideo,
                    onShareFile = { file -> onShareFile(file, "video/mp4") }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun TabAFrameTest(
    videoUri: Uri?,
    videoInfo: VideoInfo?,
    frameIndex: FrameIndex?,
    indexBuildTimeMs: Long?,
    isIndexing: Boolean,
    exactPlayer: ExactPlayer,
    currentFrame: Int,
    targetPtsUs: Long,
    lastRenderedPtsUs: Long,
    renderedCount: Int,
    frameInputText: String,
    onFrameInputChange: (String) -> Unit,
    onSelectVideo: () -> Unit,
    onRecordVideo: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onGoFrame: () -> Unit,
    isRunningSeekTest: Boolean,
    seekTestProgress: Int,
    seekTestSummary: SeekTestSummary?,
    onRunSeekTest: () -> Unit,
    isRunningDriftTest: Boolean,
    driftTestStatus: String,
    stepDriftSummary: StepDriftSummary?,
    onRunDriftTest: () -> Unit,
    onCopyCsv: (String) -> Unit,
    onShareCsv: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Video Selection & Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSelectVideo,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (videoUri != null) "ĐỔI VIDEO" else "CHỌN VIDEO", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = onRecordVideo,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4081))
                    ) {
                        Text("📹 QUAY (Cam)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                if (isIndexing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Đang dựng Frame Index từ MediaExtractor...", fontSize = 12.sp, color = Color.Yellow)
                }

                if (videoInfo != null && frameIndex != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val estFps = if (videoInfo.durationUs > 0) {
                        frameIndex.count * 1_000_000.0 / videoInfo.durationUs
                    } else 0.0

                    Text("Kích thước: ${videoInfo.width}x${videoInfo.height} (Display: ${videoInfo.displayWidth}x${videoInfo.displayHeight})", fontSize = 12.sp)
                    Text("Hướng xoay: ${videoInfo.rotation}° | Mime: ${videoInfo.mime}", fontSize = 12.sp)
                    Text("Số frame: ${frameIndex.count} | FPS ước tính: ${String.format(Locale.US, "%.2f", estFps)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text("Thời lượng: ${videoInfo.durationUs / 1000} ms | Thời gian dựng index: ${indexBuildTimeMs ?: 0} ms", fontSize = 12.sp)
                } else if (videoUri == null) {
                    Text("Chưa chọn video nào. Nhấn nút trên để chọn video kiểm tra.", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        // 2. Video Player Preview & Stepping Controls
        if (frameIndex != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Preview & Điều khiển Frame", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    // PlayerView
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(Color.Black)
                            .border(1.dp, Color.DarkGray)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exactPlayer.player
                                    useController = false
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Frame Status Labels
                    val deltaUs = lastRenderedPtsUs - targetPtsUs
                    Text(
                        text = "Frame: $currentFrame / ${frameIndex.count - 1}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "PTS đích: ${targetPtsUs} us | PTS đã render: ${lastRenderedPtsUs} us",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Độ lệch (Delta): ${deltaUs} us (${deltaUs / 1000} ms) | Render count: $renderedCount",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (abs(deltaUs) <= 1000L) Color.Green else Color(0xFFFF5252)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Buttons: Step -1, Step +1, Jump to Frame
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onStepFrame(-1) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("◀ Frame")
                        }

                        Button(
                            onClick = { onStepFrame(1) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Frame ▶")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = frameInputText,
                            onValueChange = onFrameInputChange,
                            label = { Text("Frame N") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(
                            onClick = onGoFrame,
                            modifier = Modifier.width(80.dp)
                        ) {
                            Text("Go")
                        }
                    }
                }
            }

            // 3. Automated Tests: Random Seek Test (100 frames)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Test 1: Random Seek Test (100 frames)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Tua 100 frame ngẫu nhiên, đo PTS thật đã render so với ptsUs[N], đo độ trễ (ms), xuất CSV.",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onRunSeekTest,
                        enabled = !isRunningSeekTest && !isRunningDriftTest,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(if (isRunningSeekTest) "Đang chạy ($seekTestProgress/100)..." else "BẮT ĐẦU RANDOM SEEK TEST")
                    }

                    if (isRunningSeekTest) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { seekTestProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (seekTestSummary != null) {
                        val s = seekTestSummary
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("KẾT QUẢ RANDOM SEEK TEST:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        val isPassCriteria = s.successCount >= 99 && s.medianLatencyMs < 150 && s.p95LatencyMs < 400
                        Text(
                            text = if (isPassCriteria) "TIÊU CHÍ: ĐẠT (PASS)" else "TIÊU CHÍ: CHƯA ĐẠT (FAIL)",
                            fontWeight = FontWeight.Bold,
                            color = if (isPassCriteria) Color.Green else Color.Red
                        )
                        Text("• Số lần chính xác: ${s.successCount} / ${s.totalSeeks} (Mục tiêu >= 99)", fontSize = 11.sp)
                        Text("• Offset phát hiện: ${s.detectedOffsetUs} us", fontSize = 11.sp)
                        Text("• Độ trễ Trung vị (Median): ${s.medianLatencyMs} ms (Mục tiêu < 150 ms)", fontSize = 11.sp)
                        Text("• Độ trễ p95: ${s.p95LatencyMs} ms (Mục tiêu < 400 ms)", fontSize = 11.sp)
                        Text("• Độ trễ Max: ${s.maxLatencyMs} ms", fontSize = 11.sp)
                        Text("• Tệp CSV: ${s.csvFilePath}", fontSize = 10.sp, color = Color.Gray)

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onCopyCsv(s.csvContent) }) {
                                Text("Sao chép CSV", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { onShareCsv(s.csvFilePath) }) {
                                Text("Chia sẻ tệp CSV", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 4. Automated Tests: Step Drift Test (+200, -200)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Test 2: Step Drift Test (+200 rồi -200)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Từ frame 0 bước +1 đủ 200 lần rồi -1 về 0. Báo frame cuối có đúng 0 không.",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onRunDriftTest,
                        enabled = !isRunningDriftTest && !isRunningSeekTest,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100))
                    ) {
                        Text(if (isRunningDriftTest) "Đang chạy test drift..." else "BẮT ĐẦU STEP DRIFT TEST", color = Color.Black)
                    }

                    if (driftTestStatus.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(driftTestStatus, fontSize = 11.sp, color = Color.Yellow)
                    }

                    if (stepDriftSummary != null) {
                        val d = stepDriftSummary
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("KẾT QUẢ STEP DRIFT TEST:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(
                            text = if (d.isPassed) "ĐẠT (PASS): Về đúng Frame 0 (Drift = 0 frame)" else "KHÔNG ĐẠT (FAIL): Lệch ${d.driftUs} us",
                            fontWeight = FontWeight.Bold,
                            color = if (d.isPassed) Color.Green else Color.Red
                        )
                        Text("• Số bước đã đi: ${d.stepsTaken} bước lên, ${d.stepsTaken} bước về", fontSize = 11.sp)
                        Text("• PTS ban đầu: ${d.initialPtsUs} us | PTS kết thúc: ${d.finalPtsUs} us", fontSize = 11.sp)
                        Text("• Độ lệch tuyệt đối: ${abs(d.driftUs)} us", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun TabBExportTest(
    videoUri: Uri?,
    videoInfo: VideoInfo?,
    frameIndex: FrameIndex?,
    isIndexing: Boolean,
    onSelectVideo: () -> Unit,
    onRecordVideo: () -> Unit,
    isExporting: Boolean,
    exportProgress: Int,
    exportTimeSec: Long,
    exportFileSizeMb: Double,
    exportedFile: File?,
    exportStatusText: String,
    onStartExport: () -> Unit,
    onOpenVideo: (File) -> Unit,
    onShareFile: (File) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "Export Test (Media3 Transformer)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Vẽ overlay: Viền đỏ mép khung + Text 'OVL f=<frameIndex>' ở giữa + chấm tròn CYAN chạy trái -> phải.",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSelectVideo,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (videoUri != null) "ĐỔI VIDEO" else "CHỌN VIDEO", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = onRecordVideo,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4081))
                    ) {
                        Text("📹 QUAY (Cam)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                if (videoInfo != null && frameIndex != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Video đã chọn: ${videoInfo.displayWidth}x${videoInfo.displayHeight} | Rotation: ${videoInfo.rotation}° | Frames: ${frameIndex.count}", fontSize = 12.sp)
                }
            }
        }

        if (videoInfo != null && frameIndex != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Button(
                        onClick = onStartExport,
                        enabled = !isExporting,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(if (isExporting) "ĐANG XUẤT ($exportProgress%)..." else "EXPORT WITH OVERLAY", fontWeight = FontWeight.Bold)
                    }

                    if (isExporting) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { exportProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Tiến độ: $exportProgress% | Thời gian đã chạy: ${exportTimeSec}s", fontSize = 12.sp)
                    }

                    if (exportStatusText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(exportStatusText, fontSize = 12.sp, color = Color.Yellow)
                    }

                    if (exportedFile != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("KẾT QUẢ EXPORT:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Green)
                        Text("• Thời gian export: ${exportTimeSec} giây", fontSize = 12.sp)
                        Text("• Dung lượng file: ${String.format(Locale.US, "%.2f", exportFileSizeMb)} MB", fontSize = 12.sp)
                        Text("• Đường dẫn: ${exportedFile.absolutePath}", fontSize = 10.sp, color = Color.LightGray)

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onOpenVideo(exportedFile) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Xem Video")
                            }
                            Button(
                                onClick = { onShareFile(exportedFile) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Chia sẻ File")
                            }
                        }
                    }
                }
            }

            // Information & Verification Guide Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Hướng dẫn kiểm chứng bằng ffprobe (Mục 4):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        text = "# Kiểm tra frame count file xuất:\nffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 <out.mp4>\n\n# Trích xuất frame 100 kiểm tra khớp 'OVL f=100':\nffmpeg -i <out.mp4> -vf \"select=eq(n\\,100)\" -vframes 1 f100.png",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}

// Logic for 100 Random Seeks Test
@OptIn(UnstableApi::class)
suspend fun run100RandomSeeks(
    context: Context,
    exactPlayer: ExactPlayer,
    index: FrameIndex,
    onProgress: (Int) -> Unit
): SeekTestSummary {
    val totalSeeks = 100
    val records = mutableListOf<SeekTestRecord>()
    val latencies = mutableListOf<Long>()

    // 1. Detect initial offset at frame 0
    withContext(Dispatchers.Main) {
        exactPlayer.seekToFrame(index, 0)
    }
    // Wait for frame 0 to render
    val start0Count = exactPlayer.renderedCount
    var wait0 = 0
    while (exactPlayer.renderedCount == start0Count && wait0 < 200) {
        delay(5)
        wait0++
    }
    val detectedOffsetUs = if (exactPlayer.lastRenderedPtsUs >= 0 && index.count > 0) {
        exactPlayer.lastRenderedPtsUs - index.ptsUs[0]
    } else 0L

    // 2. Run 100 random seeks
    val random = Random(42) // reproducible seed
    for (i in 0 until totalSeeks) {
        val targetFrame = random.nextInt(index.count)
        val targetPts = index.ptsUs[targetFrame]

        val initialCount = exactPlayer.renderedCount
        val startTime = SystemClock.uptimeMillis()

        withContext(Dispatchers.Main) {
            exactPlayer.seekToFrame(index, targetFrame, detectedOffsetUs)
        }

        // Wait for frame render with timeout 1000ms
        var waitedMs = 0
        while (exactPlayer.renderedCount == initialCount && waitedMs < 1000) {
            delay(5)
            waitedMs += 5
        }

        val latencyMs = SystemClock.uptimeMillis() - startTime
        val renderedPts = exactPlayer.lastRenderedPtsUs
        val deltaUs = abs(renderedPts - targetPts + detectedOffsetUs)
        val isSuccess = deltaUs <= 1000L && waitedMs < 1000

        latencies.add(latencyMs)
        records.add(
            SeekTestRecord(
                seekIndex = i + 1,
                targetFrame = targetFrame,
                targetPtsUs = targetPts,
                renderedPtsUs = renderedPts,
                deltaUs = deltaUs,
                latencyMs = latencyMs,
                isSuccess = isSuccess
            )
        )

        withContext(Dispatchers.Main) {
            onProgress(i + 1)
        }
    }

    // 3. Compute statistics
    val successCount = records.count { it.isSuccess }
    val sortedLatencies = latencies.sorted()
    val medianLatency = sortedLatencies[sortedLatencies.size / 2]
    val p95Latency = sortedLatencies[(sortedLatencies.size * 0.95).toInt().coerceAtMost(sortedLatencies.size - 1)]
    val maxLatency = sortedLatencies.last()

    // 4. Generate CSV
    val csvSb = StringBuilder()
    csvSb.append("seek_index,target_frame,target_pts_us,rendered_pts_us,delta_us,latency_ms,status\n")
    for (r in records) {
        csvSb.append("${r.seekIndex},${r.targetFrame},${r.targetPtsUs},${r.renderedPtsUs},${r.deltaUs},${r.latencyMs},${if (r.isSuccess) "PASS" else "FAIL"}\n")
    }
    val csvContent = csvSb.toString()

    // 5. Save CSV file
    val outDir = getOutputDir(context)
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val csvFile = File(outDir, "seek_test_$timeStamp.csv")
    try {
        FileWriter(csvFile).use { it.write(csvContent) }
    } catch (_: Exception) {}

    return SeekTestSummary(
        totalSeeks = totalSeeks,
        successCount = successCount,
        detectedOffsetUs = detectedOffsetUs,
        medianLatencyMs = medianLatency,
        p95LatencyMs = p95Latency,
        maxLatencyMs = maxLatency,
        csvFilePath = csvFile.absolutePath,
        csvContent = csvContent
    )
}

// Logic for Step Drift Test (+200, -200)
@OptIn(UnstableApi::class)
suspend fun runStepDriftTest(
    exactPlayer: ExactPlayer,
    index: FrameIndex,
    onStatus: (String) -> Unit
): StepDriftSummary {
    if (index.count == 0) return StepDriftSummary()

    // Seek to frame 0 first
    withContext(Dispatchers.Main) {
        exactPlayer.seekToFrame(index, 0)
    }
    var wait0 = 0
    val c0 = exactPlayer.renderedCount
    while (exactPlayer.renderedCount == c0 && wait0 < 200) {
        delay(5)
        wait0++
    }
    val initialPtsUs = exactPlayer.lastRenderedPtsUs
    val steps = minOf(200, index.count - 1)

    // Step +1 for 200 times
    for (step in 1..steps) {
        val prevCount = exactPlayer.renderedCount
        withContext(Dispatchers.Main) {
            exactPlayer.seekToFrame(index, step)
        }
        var w = 0
        while (exactPlayer.renderedCount == prevCount && w < 100) {
            delay(5)
            w += 5
        }
        if (step % 20 == 0 || step == steps) {
            withContext(Dispatchers.Main) {
                onStatus("Đang bước tới: $step / $steps...")
            }
        }
    }

    // Step -1 back to 0
    for (step in (steps - 1) downTo 0) {
        val prevCount = exactPlayer.renderedCount
        withContext(Dispatchers.Main) {
            exactPlayer.seekToFrame(index, step)
        }
        var w = 0
        while (exactPlayer.renderedCount == prevCount && w < 100) {
            delay(5)
            w += 5
        }
        if (step % 20 == 0 || step == 0) {
            withContext(Dispatchers.Main) {
                onStatus("Đang bước lùi về: $step / 0...")
            }
        }
    }

    val finalPtsUs = exactPlayer.lastRenderedPtsUs
    val driftUs = finalPtsUs - initialPtsUs
    val isPassed = abs(driftUs) <= 1000L

    return StepDriftSummary(
        stepsTaken = steps,
        initialPtsUs = initialPtsUs,
        finalPtsUs = finalPtsUs,
        driftUs = driftUs,
        isPassed = isPassed
    )
}

fun getOutputDir(context: Context): File {
    val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
    val dir = File(movies, "AceTraceSpike")
    if (dir.exists() || dir.mkdirs()) {
        return dir
    }
    val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AceTraceSpike")
    fallback.mkdirs()
    return fallback
}

fun getTotalRamMb(context: Context): Long {
    return try {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        memInfo.totalMem / (1024 * 1024)
    } catch (_: Exception) {
        0L
    }
}
