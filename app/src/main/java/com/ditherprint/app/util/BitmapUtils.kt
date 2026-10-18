package com.ditherprint.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import com.ditherprint.app.printer.PhomemoProtocol

/**
 * Shared bitmap helpers for crop/resize and safe ownership transfer.
 */
object BitmapUtils {

    /**
     * Resize [bitmap] for the given printer paper width, always returning a
     * bitmap that is exactly [PhomemoProtocol.IMAGE_WIDTH] pixels wide.
     * Landscape images are rotated to maximize printable height.
     *
     * Caller owns the returned bitmap. Input is NOT recycled.
     */
    fun resizeForPrinter(bitmap: Bitmap, targetWidth: Int): Bitmap {
        require(targetWidth > 0) { "targetWidth must be > 0" }
        require(!bitmap.isRecycled) { "bitmap is recycled" }

        val oriented = if (bitmap.width > bitmap.height) {
            val matrix = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val aspect = oriented.height.toFloat() / oriented.width.toFloat()
        val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true)
        if (oriented !== bitmap && oriented !== resized) oriented.recycle()

        // Always pad to full printer width when narrower (e.g. 15mm / 25mm paper)
        if (targetWidth < PhomemoProtocol.IMAGE_WIDTH) {
            val full = Bitmap.createBitmap(
                PhomemoProtocol.IMAGE_WIDTH, targetHeight, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(full)
            canvas.drawColor(Color.WHITE)
            val left = (PhomemoProtocol.IMAGE_WIDTH - targetWidth) / 2f
            canvas.drawBitmap(resized, left, 0f, null)
            if (resized !== bitmap) resized.recycle()
            return full
        }

        // Clamp wider-than-expected results (defensive) by center-cropping to IMAGE_WIDTH
        if (resized.width > PhomemoProtocol.IMAGE_WIDTH) {
            val cropX = (resized.width - PhomemoProtocol.IMAGE_WIDTH) / 2
            val cropped = Bitmap.createBitmap(
                resized, cropX, 0, PhomemoProtocol.IMAGE_WIDTH, resized.height
            )
            if (resized !== bitmap) resized.recycle()
            return cropped
        }

        return resized
    }

    /** Recycle [old] if it is non-null, not recycled, and different from [keep]. */
    fun recycleIfDifferent(old: Bitmap?, keep: Bitmap?) {
        if (old != null && old !== keep && !old.isRecycled) {
            old.recycle()
        }
    }
}
