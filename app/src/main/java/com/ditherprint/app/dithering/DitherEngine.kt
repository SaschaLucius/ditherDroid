package com.ditherprint.app.dithering

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Dithering engine ported from ditherbox and node-phomemo-printer.
 * Operates on grayscale pixel arrays for performance.
 */
object DitherEngine {

    /**
     * Main entry: takes an ARGB_8888 Bitmap, returns a new 1-bit-style Bitmap (black/white).
     */
    fun process(
        source: Bitmap,
        algorithm: DitherAlgorithm,
        brightness: Float = 0f,    // -1..1
        contrast: Float = 1f,      // 0..2
        invert: Boolean = false,
        bayerSize: Int = 4,
        bayerScale: Float = 1f,    // 0..2, strength of ordered dither pattern
        threshold: Float = 0.5f,   // 0..1, threshold for Threshold algorithm
        gamma: Float = 1.5f,       // 0.5..2.5, exposure/gamma curve
        errorDiffusionStrength: Float = 1f, // 0..1, color bleed reduction
        serpentine: Boolean = false  // alternate scan direction each row
    ): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to grayscale float array (0..255)
        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // Apply brightness and contrast in gamma space
        applyToneAdjustment(gray, brightness, contrast, gamma)

        // Apply dithering
        when (algorithm) {
            DitherAlgorithm.THRESHOLD -> applyThreshold(gray, width, height, threshold)
            DitherAlgorithm.BAYER -> applyBayer(gray, width, height, bayerSize, bayerScale)
            DitherAlgorithm.RANDOM -> applyRandom(gray, width, height)
            else -> {
                val kernel = algorithm.kernel ?: return toBitmap(gray, width, height, invert)
                applyErrorDiffusion(gray, width, height, kernel.offsets, kernel.divisor, errorDiffusionStrength, serpentine)
            }
        }

        return toBitmap(gray, width, height, invert)
    }

    private fun applyToneAdjustment(gray: FloatArray, brightness: Float, contrast: Float, gamma: Float) {
        for (i in gray.indices) {
            var v = gray[i]
            // Into gamma space
            v = ((v / 255f).pow(gamma)) * 255f
            // Brightness
            v += brightness * 255f
            // Contrast around midpoint
            v = ((v - 128f) * contrast) + 128f
            // Back from gamma space
            v = ((v / 255f).coerceIn(0f, 1f).pow(1f / gamma)) * 255f
            gray[i] = v.coerceIn(0f, 255f)
        }
    }

    private fun applyThreshold(gray: FloatArray, width: Int, height: Int, threshold: Float) {
        val thresholdValue = threshold * 255f
        for (i in gray.indices) {
            gray[i] = if (gray[i] < thresholdValue) 0f else 255f
        }
    }

    private fun applyBayer(gray: FloatArray, width: Int, height: Int, size: Int, scale: Float) {
        val matrix = BayerMatrix.generate(size)
        val max = size * size
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val threshold = (matrix[y % size][x % size].toFloat() / max - 0.5f) * 255f * scale
                gray[i] = if (gray[i] + threshold >= 128f) 255f else 0f
            }
        }
    }

    private fun applyRandom(gray: FloatArray, width: Int, height: Int) {
        for (i in gray.indices) {
            val threshold = (Math.random() * 255).toFloat()
            gray[i] = if (gray[i] >= threshold) 255f else 0f
        }
    }

    private fun applyErrorDiffusion(
        gray: FloatArray,
        width: Int,
        height: Int,
        offsets: List<Triple<Int, Int, Int>>,  // dx, dy, weight
        divisor: Int,
        strength: Float,
        serpentine: Boolean
    ) {
        for (y in 0 until height) {
            val leftToRight = !serpentine || y % 2 == 0
            val xRange = if (leftToRight) 0 until width else (width - 1) downTo 0

            for (x in xRange) {
                val i = y * width + x
                val oldPixel = gray[i]
                val newPixel = if (oldPixel < 128f) 0f else 255f
                val error = (oldPixel - newPixel) * strength
                gray[i] = newPixel

                for ((dx, dy, weight) in offsets) {
                    val nx = x + if (leftToRight) dx else -dx
                    val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height) {
                        val ni = ny * width + nx
                        gray[ni] = (gray[ni] + error * weight / divisor).coerceIn(0f, 255f)
                    }
                }
            }
        }
    }

    private fun toBitmap(gray: FloatArray, width: Int, height: Int, invert: Boolean): Bitmap {
        val pixels = IntArray(width * height)
        for (i in gray.indices) {
            val v = if (invert) {
                if (gray[i] >= 128f) 0 else 255
            } else {
                if (gray[i] >= 128f) 255 else 0
            }
            pixels[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
