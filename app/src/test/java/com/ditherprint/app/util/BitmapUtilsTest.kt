package com.ditherprint.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.ditherprint.app.printer.PhomemoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BitmapUtilsTest {

    @Test
    fun resizeForPrinter_padsNarrowPaperToImageWidth() {
        val source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)

        val result = BitmapUtils.resizeForPrinter(source, PhomemoProtocol.PaperSize.MM_15.widthPx)

        assertEquals(PhomemoProtocol.IMAGE_WIDTH, result.width)
        assertTrue(result.height > 0)
    }

    @Test
    fun resizeForPrinter_fullWidthPaperStaysImageWidth() {
        val source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GRAY)

        val result = BitmapUtils.resizeForPrinter(source, PhomemoProtocol.PaperSize.MM_53.widthPx)

        assertEquals(PhomemoProtocol.IMAGE_WIDTH, result.width)
    }

    @Test
    fun resizeForPrinter_rotatesLandscape() {
        val source = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)

        val result = BitmapUtils.resizeForPrinter(source, PhomemoProtocol.IMAGE_WIDTH)

        assertEquals(PhomemoProtocol.IMAGE_WIDTH, result.width)
        // After 90° rotation, original 600 height becomes the long side
        assertTrue(result.height > result.width)
    }
}
