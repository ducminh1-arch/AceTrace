package com.acetrace.app.core.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.acetrace.app.core.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FrameIndexer {

    suspend fun indexVideo(context: Context, videoUri: Uri): VideoMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()

        try {
            retriever.setDataSource(context, videoUri)
            extractor.setDataSource(context, videoUri, null)

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "1080"
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "1920"
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "0"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0"

            val width = widthStr.toIntOrNull() ?: 1080
            val height = heightStr.toIntOrNull() ?: 1920
            val rotation = rotationStr.toIntOrNull() ?: 0
            val durationUs = (durationStr.toLongOrNull() ?: 0L) * 1000L

            // Find video track
            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }

            val ptsList = mutableListOf<Long>()
            if (videoTrackIndex != -1) {
                extractor.selectTrack(videoTrackIndex)
                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0) break
                    ptsList.add(sampleTime)
                    if (!extractor.advance()) break
                }
            }

            // Ensure PTS is sorted
            ptsList.sort()

            VideoMetadata(
                uri = videoUri.toString(),
                width = width,
                height = height,
                rotationDegrees = rotation,
                durationUs = durationUs,
                frameCount = ptsList.size.coerceAtLeast(1),
                ptsUs = ptsList
            )
        } finally {
            retriever.release()
            extractor.release()
        }
    }

    /**
     * Maps a presentation timestamp to the nearest frame index using binary search.
     */
    fun findNearestFrameIndex(ptsUsList: List<Long>, targetPtsUs: Long): Int {
        if (ptsUsList.isEmpty()) return 0
        val binarySearch = ptsUsList.binarySearch(targetPtsUs)
        if (binarySearch >= 0) return binarySearch

        val insertionPoint = -(binarySearch + 1)
        if (insertionPoint == 0) return 0
        if (insertionPoint >= ptsUsList.size) return ptsUsList.size - 1

        val prev = ptsUsList[insertionPoint - 1]
        val next = ptsUsList[insertionPoint]
        return if (targetPtsUs - prev <= next - targetPtsUs) {
            insertionPoint - 1
        } else {
            insertionPoint
        }
    }
}
