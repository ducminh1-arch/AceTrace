package com.acetrace.spike

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

@OptIn(UnstableApi::class)
class ExactPlayer(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setSeekParameters(SeekParameters.EXACT)
        playWhenReady = false
    }

    @Volatile var lastRenderedPtsUs: Long = -1
    @Volatile var renderedCount: Int = 0

    init {
        player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            lastRenderedPtsUs = presentationTimeUs
            renderedCount++
        }
    }

    fun setMediaUri(uri: Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    fun seekToFrame(index: FrameIndex, frame: Int, offsetUs: Long = 0) {
        if (index.count == 0) return
        val f = frame.coerceIn(0, index.count - 1)
        val ms = (index.ptsUs[f] - offsetUs + 999) / 1000   // làm tròn lên
        player.seekTo(ms)
    }

    fun release() {
        player.release()
    }
}
