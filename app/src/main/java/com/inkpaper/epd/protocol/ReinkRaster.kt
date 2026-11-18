package com.inkpaper.epd.protocol

import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Upgraded 4-color e-ink dithering styles tuned for JD79665AA (Black/White/Yellow/Red). */
enum class RasterStyle(val modeIndex: Int, val label: String, val hint: String) {
    TEXTURE(1, "质感", "柔化 S 曲线 + 冷色抑噪 · 肤色通透保暗部"),
    GOLD(0, "鎏金", "原色还原 + Sierra · 细腻照片渐变"),
    HALFTONE(4, "网点", "Bayer 8×8 有序网点 · 漫画印刷质感"),
    LEVEL(2, "色阶", "高反差提纯无抖动 · 海报字效专用"),
    INK(3, "水墨", "高对比宣纸灰阶 · 纯净黑白素描");

    companion object {
        fun fromIndex(index: Int): RasterStyle =
            entries.firstOrNull { it.modeIndex == index } ?: TEXTURE
    }
}

object ReinkRaster {
    /** Canonical 4-color ink palette: Black (0), White (1), Yellow (2), Red (3). */
    val PALETTE = intArrayOf(
        0xFF000000.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFF5CD00.toInt(),
        0xFFC80000.toInt(),
    )

    private val ATKINSON = arrayOf(
        intArrayOf(1, 0),
        intArrayOf(2, 0),
        intArrayOf(-1, 1),
        intArrayOf(0, 1),
        intArrayOf(1, 1),
        intArrayOf(0, 2),
    )

    private val SIERRA_LITE = arrayOf(
        intArrayOf(1, 0, 2),
        intArrayOf(-1, 1, 1),
        intArrayOf(0, 1, 1),
    )

    private val BAYER_8X8 = arrayOf(
        intArrayOf(0, 48, 12, 60, 3, 51, 15, 63),
        intArrayOf(32, 16, 44, 28, 35, 19, 47, 31),
        intArrayOf(8, 56, 4, 52, 11, 59, 7, 55),
        intArrayOf(40, 24, 36, 20, 43, 27, 39, 23),
        intArrayOf(2, 50, 14, 62, 1, 49, 13, 61),
        intArrayOf(34, 18, 46, 30, 33, 17, 45, 29),
        intArrayOf(10, 58, 6, 54, 9, 57, 5, 53),
        intArrayOf(42, 26, 38, 22, 41, 25, 37, 21),
    )

    // Soft S-curve with clean highlight lift (keeps hair/skin clean while preserving shadow folds).
    private val SOFT_TEXTURE_LUT = DoubleArray(256) { v ->
        val x = v / 255.0
        val lifted = when {
            x <= 0.12 -> x * 0.72
            x >= 0.82 -> 1.0 - (1.0 - x) * 0.22
            else -> {
                val t = (x - 0.12) / (0.82 - 0.12)
                val s = t * t * (3.0 - 2.0 * t)
                0.0864 + s * (0.9604 - 0.0864)
            }
        }
        (lifted * 255.0).coerceIn(0.0, 255.0)
    }

    // High-contrast tonal curve for clean 4-color silkscreen / poster levels.
    private val LEVEL_POSTER_LUT = DoubleArray(256) { v ->
        val x = v / 255.0
        val lifted = when {
            x <= 0.15 -> x * 0.40
            x >= 0.76 -> 1.0 - (1.0 - x) * 0.15
            else -> {
                val t = (x - 0.15) / (0.76 - 0.15)
                val s = t * t * (3.0 - 2.0 * t)
                0.060 + s * (0.964 - 0.060)
            }
        }
        (lifted * 255.0).coerceIn(0.0, 255.0)
    }

    // Punchy ink-wash curve: deep black shadows and airy paper-white highlights without crushing midtones.
    private val INK_CONTRAST_LUT = DoubleArray(256) { v ->
        val x = v / 255.0
        val lifted = when {
            x <= 0.14 -> x * 0.35
            x >= 0.80 -> 1.0 - (1.0 - x) * 0.14
            else -> {
                val t = (x - 0.14) / (0.80 - 0.14)
                val s = t * t * (3.0 - 2.0 * t)
                val curved = 0.049 + s * (0.972 - 0.049)
                0.72 * curved + 0.28 * x
            }
        }
        (lifted * 255.0).coerceIn(0.0, 255.0)
    }

    fun colorForCode(code: Int): Int {
        require(code in 0..3) { "颜色码错误: $code" }
        return PALETTE[code]
    }

    /** Quantizes ARGB pixels into 2-bit palette indices [0..3]. */
    fun quantize(pixels: IntArray, width: Int, height: Int, style: RasterStyle): ByteArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) {
            "无效的出图参数"
        }
        val count = pixels.size
        val red = DoubleArray(count)
        val green = DoubleArray(count)
        val blue = DoubleArray(count)

        for (i in 0 until count) {
            val pixel = pixels[i]
            val alpha = (pixel ushr 24) / 255.0
            val whitePart = 255.0 * (1.0 - alpha)
            red[i] = ((pixel ushr 16) and 0xFF) * alpha + whitePart
            green[i] = ((pixel ushr 8) and 0xFF) * alpha + whitePart
            blue[i] = (pixel and 0xFF) * alpha + whitePart
        }

        return when (style) {
            RasterStyle.TEXTURE -> {
                applyUnsharp(red, green, blue, width, height, amount = 0.24)
                applyLut(red, green, blue, SOFT_TEXTURE_LUT)
                ditherColorSerpentine(red, green, blue, width, height, useSierra = false, strength = 1.0)
            }
            RasterStyle.GOLD -> {
                applyUnsharp(red, green, blue, width, height, amount = 0.20)
                ditherColorSerpentine(red, green, blue, width, height, useSierra = true, strength = 0.90)
            }
            RasterStyle.HALFTONE -> {
                applyUnsharp(red, green, blue, width, height, amount = 0.15)
                ditherColorBayer(red, green, blue, width, height)
            }
            RasterStyle.LEVEL -> {
                applyUnsharp(red, green, blue, width, height, amount = 0.55)
                quantizeCleanPoster(red, green, blue)
            }
            RasterStyle.INK -> {
                val luma = toPerceptualLuma(red, green, blue)
                autoStretchLuma(luma, blend = 0.80)
                for (i in luma.indices) luma[i] = INK_CONTRAST_LUT[clamp(luma[i]).roundToInt()]
                applyUnsharpSingle(luma, width, height, amount = 0.36)
                ditherMonochromeSerpentine(luma, width, height)
            }
        }
    }

    /** Unpacks 2bpp column-packed frame data back into ARGB pixel buffer for exact preview. */
    fun decodePreview(packed: ByteArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && height % 4 == 0 && width.toLong() * height / 4 == packed.size.toLong()) {
            "预览尺寸错误"
        }
        val stride = height / 4
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val fromBottom = height - 1 - y
            val block = fromBottom / 4
            val shift = 6 - 2 * (fromBottom % 4)
            for (x in 0 until width) {
                val index = (width - 1 - x) * stride + block
                val code = ((packed[index].toInt() and 0xFF) ushr shift) and 0x03
                pixels[y * width + x] = PALETTE[code]
            }
        }
        return pixels
    }

    private fun applyLut(red: DoubleArray, green: DoubleArray, blue: DoubleArray, lut: DoubleArray) {
        for (i in red.indices) {
            red[i] = lut[clamp(red[i]).roundToInt()]
            green[i] = lut[clamp(green[i]).roundToInt()]
            blue[i] = lut[clamp(blue[i]).roundToInt()]
        }
    }

    private fun applyUnsharp(
        red: DoubleArray,
        green: DoubleArray,
        blue: DoubleArray,
        width: Int,
        height: Int,
        amount: Double,
    ) {
        if (width < 3 || height < 3 || amount <= 0.0) return
        applyUnsharpSingle(red, width, height, amount)
        applyUnsharpSingle(green, width, height, amount)
        applyUnsharpSingle(blue, width, height, amount)
    }

    private fun applyUnsharpSingle(channel: DoubleArray, width: Int, height: Int, amount: Double) {
        if (width < 3 || height < 3 || amount <= 0.0) return
        val copy = channel.clone()
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val idx = row + x
                val blur = (
                    copy[idx] * 4.0 +
                        copy[idx - 1] * 2.0 + copy[idx + 1] * 2.0 +
                        copy[idx - width] * 2.0 + copy[idx + width] * 2.0 +
                        copy[idx - width - 1] + copy[idx - width + 1] +
                        copy[idx + width - 1] + copy[idx + width + 1]
                    ) / 16.0
                channel[idx] = clamp(copy[idx] + (copy[idx] - blur) * amount)
            }
        }
    }

    private fun toPerceptualLuma(red: DoubleArray, green: DoubleArray, blue: DoubleArray): DoubleArray =
        DoubleArray(red.size) { i ->
            0.2126 * red[i] + 0.7152 * green[i] + 0.0722 * blue[i]
        }

    /** Blends 2%–98% histogram contrast stretch to bring out muted monochrome detail without blowing highlights. */
    private fun autoStretchLuma(luma: DoubleArray, blend: Double) {
        val hist = IntArray(256)
        for (v in luma) hist[clamp(v).roundToInt()]++
        val total = luma.size
        val lowTarget = (total * 0.02).toInt()
        val highTarget = (total * 0.98).toInt()

        var acc = 0
        var low = 0
        while (low < 255) {
            acc += hist[low]
            if (acc >= lowTarget) break
            low++
        }
        acc = total
        var high = 255
        while (high > low) {
            acc -= hist[high]
            if (acc <= highTarget) break
            high--
        }
        if (high - low < 28) return
        val span = (high - low).toDouble()
        for (i in luma.indices) {
            val stretched = clamp((luma[i] - low) * 255.0 / span)
            luma[i] = luma[i] * (1.0 - blend) + stretched * blend
        }
    }

    /** Suppresses neutral greys and blue/cyan hues so they render cleanly in B&W without red/yellow confetti. */
    private fun suppressCoolChroma(r: Double, g: Double, b: Double): DoubleArray {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val chroma = maxC - minC
        val coolness = smooth(0.0, 36.0, b - r - 6.0) * smooth(-16.0, 24.0, b - g * 0.65)
        val weight = smooth(10.0, 44.0, chroma) * (1.0 - coolness)
        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return doubleArrayOf(
            clamp(luma + (r - luma) * weight),
            clamp(luma + (g - luma) * weight),
            clamp(luma + (b - luma) * weight),
            weight,
        )
    }

    private fun quantizeCleanPoster(red: DoubleArray, green: DoubleArray, blue: DoubleArray): ByteArray {
        val codes = ByteArray(red.size)
        for (i in red.indices) {
            val adj = suppressCoolChroma(red[i], green[i], blue[i])
            val weight = adj[3]
            var sr = LEVEL_POSTER_LUT[clamp(adj[0]).roundToInt()]
            var sg = LEVEL_POSTER_LUT[clamp(adj[1]).roundToInt()]
            var sb = LEVEL_POSTER_LUT[clamp(adj[2]).roundToInt()]
            if (weight > 0.15) {
                val ly = 0.2126 * sr + 0.7152 * sg + 0.0722 * sb
                val midMask = smooth(245.0, 175.0, ly)
                val satGain = 1.0 + 0.65 * weight * (0.25 + 0.75 * midMask)
                sr = clamp(ly + (sr - ly) * satGain)
                sg = clamp(ly + (sg - ly) * satGain)
                sb = clamp(ly + (sb - ly) * satGain)
            }
            codes[i] = nearestWeighted(
                sr,
                sg,
                sb,
                weight,
                neutralThreshold = 132.0,
                chromaPenaltyScale = 20000.0,
            ).toByte()
        }
        return codes
    }

    /**
     * Ordered 8x8 Bayer halftone preserving full 4-color mixing (including warm browns)
     * while strictly gating neutral greys and cool blues to pure B&W ordered screen dots.
     */
    private fun ditherColorBayer(
        red: DoubleArray,
        green: DoubleArray,
        blue: DoubleArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val codes = ByteArray(red.size)
        val spreadLuma = 224.0
        val spreadChroma = 164.0
        for (row in 0 until height) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val rowBase = row * width
            val bayerRow = BAYER_8X8[row and 7]
            for (col in 0 until width) {
                val idx = rowBase + col
                val adj = suppressCoolChroma(red[idx], green[idx], blue[idx])
                val weight = adj[3]
                val bias = (bayerRow[col and 7] + 0.5) / 64.0 - 0.5
                if (weight < 0.001) {
                    val luma = 0.2126 * adj[0] + 0.7152 * adj[1] + 0.0722 * adj[2] + bias * spreadLuma
                    codes[idx] = if (luma >= 127.5) 1 else 0
                } else {
                    val delta = bias * spreadChroma
                    codes[idx] = nearestWeighted(
                        clamp(adj[0] + delta),
                        clamp(adj[1] + delta),
                        clamp(adj[2] + delta),
                        weight,
                        neutralThreshold = 127.5,
                    ).toByte()
                }
            }
        }
        return codes
    }

    private fun ditherColorSerpentine(
        red: DoubleArray,
        green: DoubleArray,
        blue: DoubleArray,
        width: Int,
        height: Int,
        useSierra: Boolean,
        strength: Double,
    ): ByteArray {
        val count = red.size
        val baseR = DoubleArray(count)
        val baseG = DoubleArray(count)
        val baseB = DoubleArray(count)
        val weights = DoubleArray(count)
        for (i in 0 until count) {
            val adj = suppressCoolChroma(red[i], green[i], blue[i])
            baseR[i] = adj[0]
            baseG[i] = adj[1]
            baseB[i] = adj[2]
            weights[i] = adj[3]
        }

        val errR = FloatArray(count)
        val errG = FloatArray(count)
        val errB = FloatArray(count)
        val codes = ByteArray(count)

        for (row in 0 until height) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val dir = if ((row and 1) == 0) 1 else -1
            var col = if (dir == 1) 0 else width - 1
            val rowBase = row * width

            while (col in 0 until width) {
                val idx = rowBase + col
                val sr = clamp(baseR[idx] + errR[idx])
                val sg = clamp(baseG[idx] + errG[idx])
                val sb = clamp(baseB[idx] + errB[idx])
                val code = nearestWeighted(sr, sg, sb, weights[idx], neutralThreshold = 127.5)
                codes[idx] = code.toByte()

                val chosen = PALETTE[code]
                val dr = (sr - ((chosen ushr 16) and 0xFF)).toFloat()
                val dg = (sg - ((chosen ushr 8) and 0xFF)).toFloat()
                val db = (sb - (chosen and 0xFF)).toFloat()

                if (useSierra) {
                    val scale = (strength / 4.0).toFloat()
                    for (step in SIERRA_LITE) {
                        val nr = row + step[1]
                        val nc = col + step[0] * dir
                        if (nr >= height || nc < 0 || nc >= width) continue
                        val target = nr * width + nc
                        val f = step[2] * scale
                        errR[target] += dr * f
                        errG[target] += dg * f
                        errB[target] += db * f
                    }
                } else {
                    val scale = (strength / 8.0).toFloat()
                    for (step in ATKINSON) {
                        val nr = row + step[1]
                        val nc = col + step[0] * dir
                        if (nr >= height || nc < 0 || nc >= width) continue
                        val target = nr * width + nc
                        errR[target] += dr * scale
                        errG[target] += dg * scale
                        errB[target] += db * scale
                    }
                }
                col += dir
            }
        }
        return codes
    }

    private fun ditherMonochromeSerpentine(luma: DoubleArray, width: Int, height: Int): ByteArray {
        val count = luma.size
        val err = FloatArray(count)
        val codes = ByteArray(count)

        for (row in 0 until height) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val dir = if ((row and 1) == 0) 1 else -1
            var col = if (dir == 1) 0 else width - 1
            val rowBase = row * width

            while (col in 0 until width) {
                val idx = rowBase + col
                val sample = clamp(luma[idx] + err[idx])
                val isWhite = sample >= 127.5
                codes[idx] = if (isWhite) 1 else 0
                val delta = (sample - if (isWhite) 255.0 else 0.0).toFloat() / 8f

                for (step in ATKINSON) {
                    val nr = row + step[1]
                    val nc = col + step[0] * dir
                    if (nr >= height || nc < 0 || nc >= width) continue
                    err[nr * width + nc] += delta
                }
                col += dir
            }
        }
        return codes
    }

    /**
     * Redmean weighted distance combined with cool/neutral color gate.
     * Preserves yellow+black mixing on warm browns and skin tones while preventing
     * blue/grey noise speckles.
     */
    private fun nearestWeighted(
        red: Double,
        green: Double,
        blue: Double,
        colorWeight: Double,
        neutralThreshold: Double,
        chromaPenaltyScale: Double = 45000.0,
    ): Int {
        if (colorWeight < 0.001) {
            val luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue
            return if (luma >= neutralThreshold) 1 else 0
        }
        val sr = clamp(red).roundToInt()
        val sg = clamp(green).roundToInt()
        val sb = clamp(blue).roundToInt()
        var best = 0
        var bestDistance = Double.MAX_VALUE
        val chromaPenalty = (1.0 - colorWeight) * chromaPenaltyScale
        for (index in PALETTE.indices) {
            val c = PALETTE[index]
            val pr = (c ushr 16) and 0xFF
            val pg = (c ushr 8) and 0xFF
            val pb = c and 0xFF
            val dr = sr - pr
            val dg = sg - pg
            val db = sb - pb
            val meanR = (sr + pr) / 2
            val baseDist = ((((512L + meanR) * dr * dr) ushr 8) +
                4L * dg * dg +
                (((767L - meanR) * db * db) ushr 8)).toDouble()
            val totalDist = baseDist + if (index >= 2) chromaPenalty else 0.0
            if (totalDist < bestDistance) {
                bestDistance = totalDist
                best = index
            }
        }
        return best
    }

    private fun smooth(low: Double, high: Double, value: Double): Double {
        val pos = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
        return pos * pos * (3.0 - 2.0 * pos)
    }

    private fun clamp(v: Double): Double = max(0.0, min(255.0, v))
}
