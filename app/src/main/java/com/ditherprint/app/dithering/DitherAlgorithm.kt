package com.ditherprint.app.dithering

/**
 * Dithering algorithms ported from node-phomemo-printer/dithering.js
 * and ditherbox/src/diffusions.js.
 *
 * Each error diffusion algorithm is defined by a kernel of (dx, dy, weight) offsets
 * and a divisor (error scale).
 */
enum class DitherAlgorithm(
    val displayName: String,
    val kernel: DiffusionKernel? = null
) {
    FLOYD_STEINBERG(
        "Floyd-Steinberg",
        DiffusionKernel(
            listOf(
                Triple(1, 0, 7),
                Triple(-1, 1, 3),
                Triple(0, 1, 5),
                Triple(1, 1, 1)
            ), 16
        )
    ),
    ATKINSON(
        "Atkinson",
        DiffusionKernel(
            listOf(
                Triple(1, 0, 1),
                Triple(2, 0, 1),
                Triple(-1, 1, 1),
                Triple(0, 1, 1),
                Triple(1, 1, 1),
                Triple(0, 2, 1)
            ), 8
        )
    ),
    BURKES(
        "Burkes",
        DiffusionKernel(
            listOf(
                Triple(1, 0, 8),
                Triple(2, 0, 4),
                Triple(-2, 1, 2),
                Triple(-1, 1, 4),
                Triple(0, 1, 8),
                Triple(1, 1, 4),
                Triple(2, 1, 2)
            ), 32
        )
    ),
    STUCKI(
        "Stucki",
        DiffusionKernel(
            listOf(
                Triple(1, 0, 8),
                Triple(2, 0, 4),
                Triple(-2, 1, 2),
                Triple(-1, 1, 4),
                Triple(0, 1, 8),
                Triple(1, 1, 4),
                Triple(2, 1, 2),
                Triple(-2, 2, 1),
                Triple(-1, 2, 2),
                Triple(0, 2, 4),
                Triple(1, 2, 2),
                Triple(2, 2, 1)
            ), 42
        )
    ),
    JARVIS_JUDICE_NINKE(
        "Jarvis-Judice-Ninke",
        DiffusionKernel(
            listOf(
                Triple(1, 0, 7),
                Triple(2, 0, 5),
                Triple(-2, 1, 3),
                Triple(-1, 1, 5),
                Triple(0, 1, 7),
                Triple(1, 1, 5),
                Triple(2, 1, 3),
                Triple(-2, 2, 1),
                Triple(-1, 2, 3),
                Triple(0, 2, 5),
                Triple(1, 2, 3),
                Triple(2, 2, 1)
            ), 48
        )
    ),
    SIERRA2(
        "Sierra2",
        DiffusionKernel(
            listOf(
                Triple(1, 0, 4),
                Triple(2, 0, 3),
                Triple(-2, 1, 1),
                Triple(-1, 1, 2),
                Triple(0, 1, 3),
                Triple(1, 1, 2),
                Triple(2, 1, 1),
                Triple(-1, 2, 1),
                Triple(0, 2, 2),
                Triple(1, 2, 1)
            ), 12
        )
    ),
    PIGEON(
        "Pigeon",
        DiffusionKernel(
            listOf(
                Triple(1, 0, 2),
                Triple(2, 0, 1),
                Triple(-1, 1, 2),
                Triple(0, 1, 2),
                Triple(1, 1, 2),
                Triple(-2, 2, 1),
                Triple(0, 2, 1),
                Triple(2, 2, 1)
            ), 14
        )
    ),
    THRESHOLD("Threshold"),
    BAYER("Bayer (Ordered)"),
    RANDOM("Random");
}

data class DiffusionKernel(
    val offsets: List<Triple<Int, Int, Int>>,  // dx, dy, weight
    val divisor: Int
)
