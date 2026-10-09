package com.acetrace.app.core.video

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoRecordUtil {
    fun createVideoRecordFile(context: Context): Pair<File, Uri> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "movies").apply { mkdirs() }
        moviesDir.mkdirs()
        val file = File(moviesDir, "AceTrace_REC_${timeStamp}.mp4")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return Pair(file, uri)
    }
}
