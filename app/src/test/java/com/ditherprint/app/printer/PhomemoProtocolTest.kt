package com.ditherprint.app.printer

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhomemoProtocolTest {

    @Test
    fun buildPrintData_acceptsExactWidth() {
        val bitmap = Bitmap.createBitmap(
            PhomemoProtocol.IMAGE_WIDTH, 8, Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(Color.WHITE)
        // One black pixel
        bitmap.setPixel(0, 0, Color.BLACK)

        val data = PhomemoProtocol.buildPrintData(bitmap)
        assertTrue(data.size > 20)
        // ESC @ init
        assertEquals(27.toByte(), data[0])
        assertEquals(64.toByte(), data[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildPrintData_rejectsWrongWidth() {
        val bitmap = Bitmap.createBitmap(100, 8, Bitmap.Config.ARGB_8888)
        PhomemoProtocol.buildPrintData(bitmap)
    }

    @Test
    fun buildPrintData_replacesLineFeedByte() {
        // Construct a row where the first byte of raster data would be 0x0A
        // Bit pattern 00001010 = black on bits 4 and 6 (0-indexed from MSB: bits 3 and 5)
        val bitmap = Bitmap.createBitmap(
            PhomemoProtocol.IMAGE_WIDTH, 1, Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(Color.WHITE)
        // Set pixels that produce byte 0x0A in first byte (bits 3 and 5 set from MSB)
        // bit positions: 7 6 5 4 3 2 1 0 → for 0x0A = 00001010 → bits 1 and 3
        // From MSB (bit 7 = pixel 0): pixel indices 4 and 6
        bitmap.setPixel(4, 0, Color.BLACK)
        bitmap.setPixel(6, 0, Color.BLACK)

        val data = PhomemoProtocol.buildPrintData(bitmap)
        // Find GS v 0 block and first image byte after header
        var i = 0
        while (i < data.size - 2) {
            if (data[i] == 29.toByte() && data[i + 1] == 118.toByte() && data[i + 2] == 48.toByte()) {
                // GS v 0 mode + width(2) + height(2) = 8 bytes header after GS
                val firstImageByte = data[i + 8]
                assertEquals(
                    "0x0A should be remapped to 0x14",
                    0x14.toByte(),
                    firstImageByte
                )
                return
            }
            i++
        }
        throw AssertionError("GS v 0 block not found")
    }

    @Test
    fun parseNotification_battery() {
        val current = PhomemoProtocol.PrinterInfo()
        val result = PhomemoProtocol.parseNotification(
            byteArrayOf(0x1A, 0x04, 0xA1.toByte()),
            current
        )
        assertNotNull(result)
        assertEquals("battery", result!!.first)
        assertEquals(10, result.second.battery)
    }

    @Test
    fun parseNotification_paperOut() {
        val result = PhomemoProtocol.parseNotification(
            byteArrayOf(0x1A, 0x06, 0x88.toByte()),
            PhomemoProtocol.PrinterInfo()
        )
        assertEquals("out", result!!.second.paper)
    }

    @Test
    fun parseNotification_printDone() {
        val result = PhomemoProtocol.parseNotification(
            byteArrayOf(0x1A, 0x0B, 0xB8.toByte()),
            PhomemoProtocol.PrinterInfo(isPrinting = true)
        )
        assertEquals("print", result!!.first)
        assertFalse(result.second.isPrinting)
    }

    @Test
    fun parseNotification_ignoresUnknown() {
        assertNull(
            PhomemoProtocol.parseNotification(
                byteArrayOf(0x1A, 0x7F, 0x00),
                PhomemoProtocol.PrinterInfo()
            )
        )
    }

    @Test
    fun buildHeatSettingsPacket_containsSpeed() {
        val packet = PhomemoProtocol.buildHeatSettingsPacket(PhomemoProtocol.PrintSpeed.HIGH_QUALITY)
        assertEquals(5, packet.size)
        assertEquals(27.toByte(), packet[0])
        assertEquals(PhomemoProtocol.PrintSpeed.HIGH_QUALITY.heatTime.toByte(), packet[3])
    }
}
