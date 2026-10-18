package com.ditherprint.app.util

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Converts an RGBA_8888 [ImageProxy] to a Bitmap, correctly handling
 * rowStride / pixelStride padding that many CameraX devices use.
 */
object ImageProxyUtils {

    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) return null

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        buffer.rewind()

        val bitmap = if (rowPadding == 0 && pixelStride == 4) {
            // Tight buffer: direct copy is safe
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(buffer)
            }
        } else {
            // Copy row-by-row, skipping stride padding
            copyWithStride(buffer, width, height, pixelStride, rowStride)
        } ?: return null

        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Visible for testing: convert a flat RGBA buffer with known strides into a Bitmap.
     */
    fun copyWithStride(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ): Bitmap? {
        if (width <= 0 || height <= 0 || pixelStride <= 0 || rowStride < pixelStride * width) {
            return null
        }

        val pixels = IntArray(width * height)
        val row = ByteArray(rowStride)
        buffer.rewind()

        for (y in 0 until height) {
            val remaining = buffer.remaining()
            if (remaining < rowStride) {
                // Last row may be shorter if buffer excludes trailing padding
                if (remaining < pixelStride * width) return null
                buffer.get(row, 0, remaining)
            } else {
                buffer.get(row, 0, rowStride)
            }
            var offset = 0
            for (x in 0 until width) {
                val r = row[offset].toInt() and 0xFF
                val g = row[offset + 1].toInt() and 0xFF
                val b = row[offset + 2].toInt() and 0xFF
                val a = if (pixelStride >= 4) row[offset + 3].toInt() and 0xFF else 0xFF
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                offset += pixelStride
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
