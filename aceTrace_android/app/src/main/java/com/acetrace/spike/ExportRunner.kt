package com.acetrace.spike

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File

@OptIn(UnstableApi::class)
class ExportRunner(private val context: Context) {
    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    fun runExport(
        inputUri: Uri,
        outFile: File,
        overlay: BitmapOverlay,
        onProgress: (Int) -> Unit,
        onDone: (ExportResult) -> Unit,
        onError: (ExportException) -> Unit
    ): Transformer {
        cancel()

        outFile.parentFile?.mkdirs()
        if (outFile.exists()) {
            outFile.delete()
        }

        val effects = Effects(emptyList(), listOf(OverlayEffect(ImmutableList.of(overlay))))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(effects)
            .build()

        val trans = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopProgressPolling()
                    onDone(exportResult)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    stopProgressPolling()
                    onError(exportException)
                }
            })
            .build()

        transformer = trans
        trans.start(item, outFile.absolutePath)

        val progressHolder = ProgressHolder()
        progressRunnable = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                val state = t.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress)
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    handler.postDelayed(this, 300)
                }
            }
        }
        handler.post(progressRunnable!!)

        return trans
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    fun cancel() {
        stopProgressPolling()
        transformer?.cancel()
        transformer = null
    }
}
