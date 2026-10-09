package com.acetrace.spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay

@OptIn(UnstableApi::class)
class SpikeOverlay(
    displayW: Int,
    displayH: Int,
    private val index: FrameIndex,
    private val firstPtsUs: Long
) : BitmapOverlay() {
    // Bitmap có CÙNG tỉ lệ khung hình đầu ra (đã áp rotation), để overlay không bị méo.
    private val w = (displayW / 2).coerceAtLeast(320)
    private val h = (displayH / 2).coerceAtLeast(320)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Luân phiên 3 bitmap để không bị coi là "không đổi" và không cấp phát liên tục gây OOM
    private val pool = arrayOf(
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    )
    private var poolIndex = 0

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bmp = pool[poolIndex % 3]
        poolIndex++
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        val f = index.indexAtOrBefore(presentationTimeUs + firstPtsUs)

        // 1. Viền đỏ mỏng quanh mép khung
        paint.style = Paint.Style.STROKE
        paint.color = Color.RED
        paint.strokeWidth = 6f
        c.drawRect(3f, 3f, w - 3f, h - 3f, paint)

        // 2. Chấm tròn chạy từ trái sang phải theo frameIndex / frameCount
        paint.style = Paint.Style.FILL
        paint.color = Color.CYAN
        val progress = if (index.count > 1) f.toFloat() / (index.count - 1).coerceAtLeast(1) else 0f
        c.drawCircle(w * progress, h * 0.5f, w * 0.03f, paint)

        // 3. Chữ OVL f=<frameIndex> ở giữa
        paint.color = Color.WHITE
        paint.textSize = h * 0.05f
        paint.textAlign = Paint.Align.CENTER
        c.drawText("OVL f=$f", w / 2f, h / 2f + h * 0.1f, paint)

        return bmp
    }
}
