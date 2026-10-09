package com.acetrace.spike

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.ArrayList

data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val mime: String
) {
    // Kích thước theo hướng hiển thị (đã áp rotation)
    val displayWidth get() = if (rotation % 180 == 0) width else height
    val displayHeight get() = if (rotation % 180 == 0) height else width
}

class FrameIndex(val ptsUs: LongArray) {
    val count get() = ptsUs.size

    /** Frame có pts <= timeUs, gần nhất. */
    fun indexAtOrBefore(timeUs: Long): Int {
        if (ptsUs.isEmpty()) return 0
        var lo = 0
        var hi = ptsUs.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (ptsUs[mid] <= timeUs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

object FrameIndexBuilder {
    fun build(context: Context, uri: Uri): Pair<VideoInfo, FrameIndex> {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                track = i
                fmt = f
                break
            }
        }
        require(track >= 0 && fmt != null) { "No video track found in uri: $uri" }
        ex.selectTrack(track)

        val times = ArrayList<Long>()
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            times.add(t)
            if (!ex.advance()) break
        }
        ex.release()
        times.sort() // sample đọc theo thứ tự decode (có B-frame), phải sắp lại theo thứ tự hiển thị

        var rotation = 0
        if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
            rotation = fmt.getInteger(MediaFormat.KEY_ROTATION)
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                rotation = rotStr?.toIntOrNull() ?: 0
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        val width = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 1080
        val height = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 1920
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else {
            if (times.isNotEmpty()) times.last() - times.first() else 0L
        }
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "video/mp4"

        val info = VideoInfo(
            width = width,
            height = height,
            rotation = rotation,
            durationUs = durationUs,
            mime = mime
        )
        return info to FrameIndex(times.toLongArray())
    }
}
