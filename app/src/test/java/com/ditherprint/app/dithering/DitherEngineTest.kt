package com.ditherprint.app.dithering

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DitherEngineTest {

    @Test
    fun threshold_makesPureBlackAndWhite() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        // Dark pixels
        for (y in 0 until 2) {
            for (x in 0 until 4) source.setPixel(x, y, Color.rgb(20, 20, 20))
        }
        // Light pixels
        for (y in 2 until 4) {
            for (x in 0 until 4) source.setPixel(x, y, Color.rgb(240, 240, 240))
        }

        val result = DitherEngine.process(
            source = source,
            algorithm = DitherAlgorithm.THRESHOLD,
            threshold = 0.5f,
            gamma = 1f
        )

        assertEquals(4, result.width)
        assertEquals(4, result.height)
        // Top should be black (print), bottom white
        assertEquals(Color.BLACK, result.getPixel(0, 0))
        assertEquals(Color.WHITE, result.getPixel(0, 3))
    }

    @Test
    fun invert_flipsResult() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(20, 20, 20))

        val normal = DitherEngine.process(
            source = source,
            algorithm = DitherAlgorithm.THRESHOLD,
            invert = false,
            threshold = 0.5f,
            gamma = 1f
        )
        val inverted = DitherEngine.process(
            source = source,
            algorithm = DitherAlgorithm.THRESHOLD,
            invert = true,
            threshold = 0.5f,
            gamma = 1f
        )

        assertEquals(Color.BLACK, normal.getPixel(0, 0))
        assertEquals(Color.WHITE, inverted.getPixel(0, 0))
    }

    @Test
    fun floydSteinberg_producesBinaryPixels() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GRAY)

        val result = DitherEngine.process(
            source = source,
            algorithm = DitherAlgorithm.FLOYD_STEINBERG,
            gamma = 1f
        )

        var black = 0
        var white = 0
        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                when (result.getPixel(x, y)) {
                    Color.BLACK -> black++
                    Color.WHITE -> white++
                    else -> throw AssertionError("Non-binary pixel at $x,$y")
                }
            }
        }
        assertTrue(black > 0)
        assertTrue(white > 0)
    }
}
