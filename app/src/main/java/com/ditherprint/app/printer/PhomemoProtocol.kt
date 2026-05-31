package com.ditherprint.app.printer

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Phomemo ESC/POS protocol encoder.
 * Ported from node-phomemo-printer/index.js.
 *
 * Converts a dithered B&W bitmap into the byte stream the printer expects.
 */
object PhomemoProtocol {

    const val BYTES_PER_LINE = 70
    const val IMAGE_WIDTH = BYTES_PER_LINE * 8  // 560 pixels

    /** Paper sizes and their pixel widths at 300dpi */
    enum class PaperSize(val widthPx: Int, val label: String) {
        MM_53(560, "53mm"),
        MM_25(200, "25mm"),
        MM_15(120, "15mm")
    }

    /** Density levels matching the Phomemo protocol */
    enum class Density(val value: Int, val label: String) {
        LOWEST(0x00, "Lowest"),
        VERY_SOFT(0x1A, "Very Soft"),
        SOFT(0x33, "Soft"),
        LIGHT(0x4D, "Light"),
        DEFAULT(0x5E, "Default"),
        MEDIUM_SOFT(0x66, "Medium Soft"),
        MIDTONE(0x80, "Midtone"),
        MEDIUM(0x99, "Medium"),
        MEDIUM_STRONG(0xB3, "Medium Strong"),
        STRONG(0xCC, "Strong"),
        VERY_STRONG(0xE6, "Very Strong"),
        HIGHEST(0xFF, "Highest")
    }

    /**
     * Build the density control packet (31 bytes).
     * Density byte is at offset 22.
     */
    fun buildDensityPacket(density: Density): ByteArray {
        return byteArrayOf(
            0x02, 0x08, 0x00, 0x1A,
            0x00, 0x16, 0x00, 0x41,
            0x00, 0x0B, 0xFF.toByte(), 0x23,
            0x01, 0x1B, 0x40, 0x1F,
            0x11, 0x02, 0x04, 0x1F,
            0x11, 0x37, density.value.toByte(), 0x1F,
            0x11, 0x0B, 0x1F, 0x11,
            0x35, 0x00, 0x86.toByte()
        )
    }

    /**
     * Build the complete print data from a dithered B&W bitmap.
     * The bitmap should already be IMAGE_WIDTH pixels wide and dithered.
     */
    fun buildPrintData(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        require(width == IMAGE_WIDTH) { "Bitmap must be $IMAGE_WIDTH pixels wide, got $width" }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val data = mutableListOf<Byte>()

        // --- HEADER ---
        // ESC @ : reset/init printer
        data.add(27)  // ESC
        data.add(64)  // @
        // ESC a 0 : left justified
        data.add(27)  // ESC
        data.add(97)  // a
        data.add(0)   // left
        // Printer-specific init bytes
        data.add(31)
        data.add(17)
        data.add(2)
        data.add(4)

        // --- IMAGE DATA ---
        var remaining = height
        var line = 0

        while (remaining > 0) {
            val blockLines = minOf(remaining, 256)

            // GS v 0 : raster bit image command
            data.add(29)   // GS
            data.add(118)  // v
            data.add(48)   // 0
            data.add(0)    // mode: normal (no scaling)

            // Horizontal bytes (16-bit LE)
            data.add(BYTES_PER_LINE.toByte())
            data.add(0)

            // Block height - 1 (16-bit LE)
            data.add((blockLines - 1).toByte())
            data.add(0)

            remaining -= blockLines

            for (l in 0 until blockLines) {
                for (x in 0 until BYTES_PER_LINE) {
                    var byte = 0
                    for (bit in 0 until 8) {
                        val pixelX = x * 8 + bit
                        val pixelY = line
                        val idx = pixelY * width + pixelX
                        val c = pixels[idx]
                        val r = Color.red(c)
                        // Black pixel (r == 0) means "print" → set bit
                        if (r == 0) {
                            byte = byte or (1 shl (7 - bit))
                        }
                    }
                    // Avoid protocol conflict: 0x0A (line feed) → 0x14
                    if (byte == 0x0A) byte = 0x14
                    data.add(byte.toByte())
                }
                line++
            }
        }

        // --- FOOTER ---
        // ESC d 2 : feed 2 lines (twice)
        data.add(27); data.add(100); data.add(2)
        data.add(27); data.add(100); data.add(2)
        // End-of-job markers
        data.add(31); data.add(17); data.add(8)
        data.add(31); data.add(17); data.add(14)
        data.add(31); data.add(17); data.add(7)
        data.add(31); data.add(17); data.add(9)

        return data.toByteArray()
    }
}
