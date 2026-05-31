package com.ditherprint.app.dithering

/**
 * Bayer matrix generator ported from ditherbox/src/bayer.js.
 * Generates recursive threshold matrices for ordered dithering.
 */
object BayerMatrix {

    private val bayer2 = intArrayOf(0, 2, 3, 1)

    /**
     * Generate a Bayer threshold matrix of the given size (must be power of 2, >= 2).
     * Returns a 2D array [y][x] of threshold values.
     */
    fun generate(size: Int): Array<IntArray> {
        require(size >= 2 && size and (size - 1) == 0) { "Size must be a power of 2, >= 2" }

        val flat = generateFlat(size)
        return Array(size) { y ->
            IntArray(size) { x ->
                flat[y * size + x]
            }
        }
    }

    private fun generateFlat(size: Int): IntArray {
        if (size == 2) return bayer2.clone()

        val length = size * size
        val prevSize = size / 2
        val prev = generateFlat(prevSize)
        val result = IntArray(length)

        for (i in 0 until length) {
            val x = i % size
            val y = i / size
            result[i] = prev[(y % prevSize) * prevSize + (x % prevSize)] * 4 +
                    bayer2[(y / prevSize) * 2 + (x / prevSize)]
        }

        return result
    }
}
