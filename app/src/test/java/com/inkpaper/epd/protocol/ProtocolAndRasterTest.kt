package com.inkpaper.epd.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ProtocolAndRasterTest {
    @Test
    fun panelTypePackPreviewAndWireScanMatch() {
        val width = PanelProtocol.WIDTH
        val height = PanelProtocol.HEIGHT
        val stride = height / 4
        val codes = ByteArray(width * height)
        val random = Random(615297)
        for (i in codes.indices) codes[i] = random.nextInt(4).toByte()
        val original = codes.clone()

        val linear = PanelProtocol.packCodes(codes, width, height)
        assertArrayEquals("A1 keeps factory row order", linear, PanelType.A1.pack(codes))
        assertArrayEquals("A0 interleaves factory rows", PanelProtocol.mapA0Rows(linear), PanelType.A0.pack(codes))

        for (type in PanelType.entries) {
            assertEquals(type, PanelType.fromName(type.name))
            val packed = type.pack(codes)
            val preview = type.preview(packed)
            for (i in codes.indices) {
                assertEquals(
                    "$type preview mismatch at pixel $i",
                    ReinkRaster.colorForCode(codes[i].toInt()),
                    preview[i],
                )
            }

            for (topDown in listOf(false, true)) {
                val wire = PanelType.forScan(packed, topDown)
                for (row in 0 until width) {
                    val sourceRow = if (type == PanelType.A1) row
                    else if (row % 2 == 0) row / 2 else width - 1 - row / 2
                    var x = width - 1 - sourceRow
                    if (type == PanelType.A0X) x = (x + width / 2) % width
                    for (block in 0 until stride) {
                        var expected = 0
                        for (pixel in 0 until 4) {
                            var y = block * 4 + pixel
                            if (!topDown) y = height - 1 - y
                            expected = (expected shl 2) or (codes[y * width + x].toInt() and 0xFF)
                        }
                        assertEquals(
                            "$type wire mismatch row=$row block=$block topDown=$topDown",
                            expected,
                            wire[row * stride + block].toInt() and 0xFF,
                        )
                    }
                }
            }
        }
        assertArrayEquals(original, codes)
    }

    @Test
    fun d4DefaultsToOfficialCheck() {
        val cmd = PanelProtocol.d4()
        assertArrayEquals(PanelProtocol.hex("F0D4850000"), cmd)
        val fast = PanelProtocol.d4(officialCheck = false)
        assertArrayEquals(PanelProtocol.hex("F0D4858000"), fast)
    }

    @Test
    fun compressedLzoShrinksSolidBlocksAndCapsAtLiteralSize() {
        val uniform = ByteArray(2000) { 0x55 }
        val compressed = PanelProtocol.compressedLzo(uniform)
        assertTrue("Expected solid block to compress well, got ${compressed.size}", compressed.size < 40)

        val random = ByteArray(2000)
        Random(42).nextBytes(random)
        val highEntropy = PanelProtocol.compressedLzo(random)
        val literal = PanelProtocol.literalLzo(random)
        assertTrue("High-entropy LZO must not exceed literal", highEntropy.size <= literal.size)
    }

    @Test
    fun quantizeMapsCanonicalColorsForAllStyles() {
        val pixels = intArrayOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFF5CD00.toInt(),
            0xFFC80000.toInt(),
        )
        for (style in RasterStyle.entries) {
            val codes = ReinkRaster.quantize(pixels, 2, 2, style)
            assertEquals(4, codes.size)
            assertTrue(codes.all { it in 0..3 })
        }
        val exactCodes = ReinkRaster.quantize(pixels, 2, 2, RasterStyle.LEVEL)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), exactCodes)
    }

    @Test
    fun neutralAndCoolRegionsAvoidRedYellowNoise() {
        val w = 32
        val h = 32
        val blueSky = IntArray(w * h) { i ->
            val t = (i / w) * 4 + 80
            (0xFF shl 24) or (40 shl 16) or (t shl 8) or 230
        }
        val neutralGrey = IntArray(w * h) { i ->
            val v = 40 + (i % w) * 6
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        for (style in RasterStyle.entries) {
            val skyCodes = ReinkRaster.quantize(blueSky, w, h, style)
            assertTrue("$style polluted blue sky with red/yellow", skyCodes.all { it.toInt() == 0 || it.toInt() == 1 })
            val greyCodes = ReinkRaster.quantize(neutralGrey, w, h, style)
            assertTrue("$style polluted neutral grey with red/yellow", greyCodes.all { it.toInt() == 0 || it.toInt() == 1 })
        }
    }

    @Test
    fun inkStretchesFlatContrastAndStaysMonochrome() {
        val w = 32
        val h = 32
        val flat = IntArray(w * h) { i ->
            val v = if (i < w * h / 2) 95 else 155
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val codes = ReinkRaster.quantize(flat, w, h, RasterStyle.INK)
        assertTrue(codes.all { it.toInt() == 0 || it.toInt() == 1 })
        assertTrue(codes.any { it.toInt() == 0 } && codes.any { it.toInt() == 1 })
    }

    @Test
    fun halftoneBayerAndLevelBehaveDistinctly() {
        assertEquals(5, RasterStyle.entries.size)
        val w = 16
        val h = 16
        val warmAmber = IntArray(w * h) { (0xFF shl 24) or (215 shl 16) or (135 shl 8) or 35 }
        val halftoneCodes = ReinkRaster.quantize(warmAmber, w, h, RasterStyle.HALFTONE)
        assertTrue("Halftone should dither warm amber", halftoneCodes.toSet().size >= 2)
        val levelCodes = ReinkRaster.quantize(warmAmber, w, h, RasterStyle.LEVEL)
        assertTrue("Level poster should render warm amber as yellow or red", levelCodes.all { it.toInt() == 2 || it.toInt() == 3 })
    }
}
