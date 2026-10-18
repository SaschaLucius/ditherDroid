package com.ditherprint.app.util

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class ImageProxyUtilsTest {

    @Test
    fun copyWithStride_handlesRowPadding() {
        val width = 2
        val height = 2
        val pixelStride = 4
        val rowStride = 12 // 8 bytes of pixels + 4 bytes padding
        val buffer = ByteBuffer.allocate(rowStride * height)

        // Row 0: red, green + pad
        putRgba(buffer, 255, 0, 0, 255)
        putRgba(buffer, 0, 255, 0, 255)
        buffer.put(ByteArray(4)) // padding
        // Row 1: blue, white + pad
        putRgba(buffer, 0, 0, 255, 255)
        putRgba(buffer, 255, 255, 255, 255)
        buffer.put(ByteArray(4))
        buffer.rewind()

        val bitmap = ImageProxyUtils.copyWithStride(buffer, width, height, pixelStride, rowStride)
        assertNotNull(bitmap)
        assertEquals(width, bitmap!!.width)
        assertEquals(height, bitmap.height)

        assertEquals(Color.RED, bitmap.getPixel(0, 0))
        assertEquals(Color.GREEN, bitmap.getPixel(1, 0))
        assertEquals(Color.BLUE, bitmap.getPixel(0, 1))
        assertEquals(Color.WHITE, bitmap.getPixel(1, 1))
    }

    @Test
    fun copyWithStride_rejectsInvalidStride() {
        val buffer = ByteBuffer.allocate(8)
        assertNull(ImageProxyUtils.copyWithStride(buffer, 2, 1, 4, 4))
    }

    private fun putRgba(buffer: ByteBuffer, r: Int, g: Int, b: Int, a: Int) {
        buffer.put(r.toByte())
        buffer.put(g.toByte())
        buffer.put(b.toByte())
        buffer.put(a.toByte())
    }
}
