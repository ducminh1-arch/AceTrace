package com.acetrace.app.core.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.acetrace.app.core.model.VideoMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val currentFrameIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val totalFrames: Int = 0,
    val isReady: Boolean = false
)

@UnstableApi
class PlayerController(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var videoMetadata: VideoMetadata? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    fun initialize(metadata: VideoMetadata, onPlayerCreated: (ExoPlayer) -> Unit) {
        videoMetadata = metadata
        val player = ExoPlayer.Builder(context).build().apply {
            // Guarantee exact seek parameters
            setSeekParameters(SeekParameters.EXACT)
            val mediaItem = MediaItem.fromUri(Uri.parse(metadata.uri))
            setMediaItem(mediaItem)
            prepare()
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val ready = playbackState == Player.STATE_READY
                    _playerState.value = _playerState.value.copy(
                        isReady = ready,
                        totalFrames = metadata.frameCount
                    )
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                }
            })
        }
        exoPlayer = player
        onPlayerCreated(player)
    }

    fun updateCurrentTime(positionMs: Long) {
        val meta = videoMetadata ?: return
        val ptsUs = positionMs * 1000L
        val frame = FrameIndexer.findNearestFrameIndex(meta.ptsUs, ptsUs)
        _playerState.value = _playerState.value.copy(
            currentFrameIndex = frame,
            currentPositionMs = positionMs
        )
    }

    fun seekToFrame(frameIndex: Int) {
        val meta = videoMetadata ?: return
        val player = exoPlayer ?: return
        val clampedFrame = frameIndex.coerceIn(0, (meta.frameCount - 1).coerceAtLeast(0))

        if (meta.ptsUs.isNotEmpty() && clampedFrame < meta.ptsUs.size) {
            val targetPtsUs = meta.ptsUs[clampedFrame]
            val targetMs = targetPtsUs / 1000L
            player.seekTo(targetMs)
            _playerState.value = _playerState.value.copy(
                currentFrameIndex = clampedFrame,
                currentPositionMs = targetMs
            )
        }
    }

    fun step(deltaFrames: Int) {
        val current = _playerState.value.currentFrameIndex
        seekToFrame(current + deltaFrames)
    }

    fun play() {
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun togglePlay() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
