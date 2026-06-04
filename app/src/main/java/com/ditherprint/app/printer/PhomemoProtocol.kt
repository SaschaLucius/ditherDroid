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

    /** Print speed / heat time levels. Higher heatTime = darker but slower. */
    enum class PrintSpeed(val heatTime: Int, val label: String) {
        FAST(40, "Fast"),
        MEDIUM_FAST(60, "Medium Fast"),
        NORMAL(80, "Normal"),
        HIGH_QUALITY(120, "High Quality"),
        MAX_QUALITY(160, "Max Quality")
    }

    /** Image alignment on paper via ESC a command. */
    enum class Alignment(val value: Int, val label: String) {
        LEFT(0, "Left"),
        CENTER(1, "Center"),
        RIGHT(2, "Right")
    }

    /** Default paper feed after print (in dots). */
    const val DEFAULT_PAPER_FEED = 4
    const val MIN_PAPER_FEED = 0
    const val MAX_PAPER_FEED = 50

    /** Printer status information parsed from BLE notifications. */
    data class PrinterInfo(
        val battery: Int? = null,
        val paper: String? = null,
        val firmware: String? = null,
        val serial: String? = null,
        val cover: String? = null,
        val version: String? = null,
        val mac: String? = null,
        val isPrinting: Boolean = false
    )

    /** Query commands to request printer status via BLE. Format: [0x1F, 0x11, X] */
    object QueryCommands {
        val BATTERY = byteArrayOf(0x1F, 0x11, 0x08)
        val PAPER = byteArrayOf(0x1F, 0x11, 0x11)
        val FIRMWARE = byteArrayOf(0x1F, 0x11, 0x07)
        val SERIAL = byteArrayOf(0x1F, 0x11, 0x09)
        val COVER = byteArrayOf(0x1F, 0x11, 0x12)
        val VERSION = byteArrayOf(0x1F, 0x11, 0x33)
        val MAC = byteArrayOf(0x1F, 0x11, 0x20)
    }

    /**
     * Build ESC 7 heat settings packet.
     * Controls print darkness/speed tradeoff.
     * @param maxDots simultaneous dots heated (default 7)
     * @param heatTime heating duration; higher = darker/slower (range ~3-255)
     * @param heatInterval recovery time between groups (default 2)
     */
    fun buildHeatSettingsPacket(
        speed: PrintSpeed,
        maxDots: Int = 7,
        heatInterval: Int = 2
    ): ByteArray {
        return byteArrayOf(0x1B, 0x37, maxDots.toByte(), speed.heatTime.toByte(), heatInterval.toByte())
    }

    /**
     * Parse a BLE notification response into a field update for PrinterInfo.
     * Response format: 0x1A, type, data...
     * Returns a pair of (field name, updated PrinterInfo) or null if unrecognized.
     */
    fun parseNotification(data: ByteArray, current: PrinterInfo): Pair<String, PrinterInfo>? {
        if (data.size < 3 || data[0] != 0x1A.toByte()) return null

        val type = data[1].toInt() and 0xFF
        return when (type) {
            0x04 -> { // Battery
                val raw = data[2].toInt() and 0xFF
                val level = when (raw) {
                    0xA4 -> 0
                    0xA3 -> 3
                    0xA2 -> 5
                    0xA1 -> 10
                    else -> raw
                }
                "battery" to current.copy(battery = level)
            }
            0x05 -> { // Cover
                val raw = data[2].toInt() and 0xFF
                val status = when (raw) {
                    0x98 -> "open"
                    0x99 -> "closed"
                    else -> "unknown"
                }
                "cover" to current.copy(cover = status)
            }
            0x06 -> { // Paper
                val raw = data[2].toInt() and 0xFF
                val status = if (raw == 0x88) "out" else "ok"
                "paper" to current.copy(paper = status)
            }
            0x07 -> { // Firmware
                val version = data.drop(2).joinToString(".") { (it.toInt() and 0xFF).toString() }
                "firmware" to current.copy(firmware = version)
            }
            0x08 -> { // Serial
                val serial = String(data, 2, data.size - 2, Charsets.US_ASCII)
                "serial" to current.copy(serial = serial)
            }
            0x0B -> { // Print status
                val raw = data[2].toInt() and 0xFF
                val printing = raw != 0xB8.toInt() && raw != 0x00
                "print" to current.copy(isPrinting = printing)
            }
            0x0D -> { // MAC address
                val mac = String(data, 2, data.size - 2, Charsets.US_ASCII)
                "mac" to current.copy(mac = mac)
            }
            0x11 -> { // Version
                val version = data.drop(2).joinToString(".") { (it.toInt() and 0xFF).toString() }
                "version" to current.copy(version = version)
            }
            else -> null
        }
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
     * @param alignment text/image alignment on paper
     * @param paperFeed number of dot-lines to feed after printing (0-50)
     */
    fun buildPrintData(
        bitmap: Bitmap,
        alignment: Alignment = Alignment.LEFT,
        paperFeed: Int = DEFAULT_PAPER_FEED
    ): ByteArray {
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
        // ESC a n : set justification
        data.add(27)  // ESC
        data.add(97)  // a
        data.add(alignment.value.toByte())
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
        // ESC d n : feed n lines (configurable)
        val feedValue = paperFeed.coerceIn(MIN_PAPER_FEED, MAX_PAPER_FEED)
        if (feedValue > 0) {
            data.add(27); data.add(100); data.add(feedValue.toByte())
        }
        // End-of-job markers
        data.add(31); data.add(17); data.add(8)
        data.add(31); data.add(17); data.add(14)
        data.add(31); data.add(17); data.add(7)
        data.add(31); data.add(17); data.add(9)

        return data.toByteArray()
    }
}
