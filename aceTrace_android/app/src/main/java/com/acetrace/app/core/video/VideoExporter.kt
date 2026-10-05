package com.acetrace.app.core.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.acetrace.app.core.model.Project
import com.acetrace.app.core.render.OverlayRenderer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

sealed class ExportState {
    data class Progress(val percentage: Int) : ExportState()
    data class Success(val outputFile: File) : ExportState()
    data class Failure(val error: Throwable) : ExportState()
}

@UnstableApi
class VideoExporter(private val context: Context) {

    fun exportVideo(
        project: Project,
        outputFile: File
    ): Flow<ExportState> = callbackFlow {
        val inputUri = Uri.parse(project.video.uri)
        val mediaItem = MediaItem.fromUri(inputUri)

        val targetWidth = if (project.video.width > 0) project.video.width else 1080
        val targetHeight = if (project.video.height > 0) project.video.height else 1920

        // Create reusable bitmap & canvas for streaming overlay frame-by-frame
        val overlayBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val overlayCanvas = Canvas(overlayBitmap)

        val bitmapOverlay = object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                overlayBitmap.eraseColor(Color.TRANSPARENT)
                val frameIndex = FrameIndexer.findNearestFrameIndex(
                    project.video.ptsUs,
                    presentationTimeUs
                )
                OverlayRenderer.render(
                    frameIndex = frameIndex,
                    project = project,
                    canvas = overlayCanvas,
                    canvasWidth = overlayBitmap.width.toFloat(),
                    canvasHeight = overlayBitmap.height.toFloat()
                )
                return overlayBitmap
            }
        }

        val overlayEffect = OverlayEffect(ImmutableList.of(bitmapOverlay as TextureOverlay))
        val videoEffects: List<Effect> = listOf(overlayEffect)

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        var transformer: Transformer? = null

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                trySend(ExportState.Success(outputFile))
                close()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                trySend(ExportState.Failure(exportException))
                close(exportException)
            }
        }

        transformer = Transformer.Builder(context)
            .addListener(listener)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()

        transformer.start(editedMediaItem, outputFile.absolutePath)

        // Polling progress periodically
        val progressHolder = ProgressHolder()
        val timer = java.util.Timer()
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val currentTransformer = transformer ?: return
                val progressState = currentTransformer.getProgress(progressHolder)
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    trySend(ExportState.Progress(progressHolder.progress))
                }
            }
        }, 200, 200)

        awaitClose {
            timer.cancel()
            transformer?.cancel()
            if (!overlayBitmap.isRecycled) {
                overlayBitmap.recycle()
            }
        }
    }
}
