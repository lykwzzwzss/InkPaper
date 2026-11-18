package com.inkpaper.epd.imaging

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import com.inkpaper.epd.protocol.PanelProtocol
import com.inkpaper.epd.protocol.PanelType
import com.inkpaper.epd.protocol.RasterStyle
import com.inkpaper.epd.protocol.ReinkRaster
import java.io.File
import java.io.FileOutputStream
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PreparedFrame(
    val preview: Bitmap,
    val packed: ByteArray,
    val nonWhitePercent: Int,
    val panelType: PanelType,
    val style: RasterStyle,
)

object ImageProcessor {
    private const val THUMB_WIDTH = PanelProtocol.WIDTH / 2
    private const val THUMB_HEIGHT = PanelProtocol.HEIGHT / 2

    fun loadFromUri(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片尺寸" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("图片解码失败")

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val norm = ((degrees % 360) + 360) % 360
        if (norm == 0) return source
        val matrix = Matrix().apply { setRotate(norm.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Area-average downscales high-res inputs onto the exact 552x768 grid to avoid moire and sensor noise. */
    fun fittedPixels(source: Bitmap, rotation: Int = 0, cover: Boolean = true): IntArray {
        val input = rotate(source, rotation)
        return fitAreaAverage(input, PanelProtocol.WIDTH, PanelProtocol.HEIGHT, cover)
    }

    fun prepare(pixels: IntArray, panelType: PanelType, style: RasterStyle): PreparedFrame {
        require(pixels.size == PanelProtocol.WIDTH * PanelProtocol.HEIGHT) { "像素尺寸错误" }
        val codes = ReinkRaster.quantize(pixels, PanelProtocol.WIDTH, PanelProtocol.HEIGHT, style)
        return prepareFromCodes(codes, panelType, style)
    }

    fun prepareFromCodes(
        codes: ByteArray,
        panelType: PanelType,
        style: RasterStyle,
        existingPreview: Bitmap? = null,
    ): PreparedFrame {
        val packed = panelType.pack(codes)
        val previewBitmap = existingPreview ?: Bitmap.createBitmap(
            panelType.preview(packed),
            PanelProtocol.WIDTH,
            PanelProtocol.HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        return PreparedFrame(
            preview = previewBitmap,
            packed = packed,
            nonWhitePercent = nonWhitePercent(codes),
            panelType = panelType,
            style = style,
        )
    }

    fun thumbnails(fullPixels: IntArray): Map<RasterStyle, Bitmap> {
        require(fullPixels.size == PanelProtocol.WIDTH * PanelProtocol.HEIGHT) { "像素尺寸错误" }
        val subsampled = IntArray(THUMB_WIDTH * THUMB_HEIGHT)
        for (row in 0 until THUMB_HEIGHT) {
            val srcRowBase = row * 2 * PanelProtocol.WIDTH
            val dstRowBase = row * THUMB_WIDTH
            for (col in 0 until THUMB_WIDTH) {
                subsampled[dstRowBase + col] = fullPixels[srcRowBase + col * 2]
            }
        }
        return RasterStyle.entries.associateWith { style ->
            val codes = ReinkRaster.quantize(subsampled, THUMB_WIDTH, THUMB_HEIGHT, style)
            val packed = PanelProtocol.packCodes(codes, THUMB_WIDTH, THUMB_HEIGHT)
            val argb = ReinkRaster.decodePreview(packed, THUMB_WIDTH, THUMB_HEIGHT)
            Bitmap.createBitmap(argb, THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888)
        }
    }

    fun saveLastOriginal(context: Context, bitmap: Bitmap) {
        writeBitmapToFiles(lastOriginalFiles(context), bitmap)
    }

    fun saveLastCrop(context: Context, bitmap: Bitmap) {
        writeBitmapToFiles(lastCropFiles(context), bitmap)
    }

    /** Returns `(bitmap, isCropped)` if a cached image from a previous session exists. */
    fun restoreLastImage(context: Context): Pair<Bitmap, Boolean>? {
        val cropFile = lastCropFiles(context).lastOrNull { it.exists() }
        if (cropFile != null) {
            BitmapFactory.decodeFile(cropFile.absolutePath)?.let { return it to true }
        }
        val origFile = lastOriginalFiles(context).lastOrNull { it.exists() }
        if (origFile != null) {
            BitmapFactory.decodeFile(origFile.absolutePath)?.let { return it to false }
        }
        return null
    }

    fun restoreLastOriginal(context: Context): Bitmap? {
        val origFile = lastOriginalFiles(context).lastOrNull { it.exists() } ?: return null
        return BitmapFactory.decodeFile(origFile.absolutePath)
    }

    private fun writeBitmapToFiles(targets: List<File>, bitmap: Bitmap) {
        for (target in targets) {
            runCatching {
                FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }
        }
    }

    private fun lastOriginalFiles(context: Context): List<File> =
        listOfNotNull(File(context.filesDir, "last.png"), context.getExternalFilesDir(null)?.let { File(it, "last.png") })

    private fun lastCropFiles(context: Context): List<File> =
        listOfNotNull(File(context.filesDir, "last-crop.png"), context.getExternalFilesDir(null)?.let { File(it, "last-crop.png") })

    private fun nonWhitePercent(codes: ByteArray): Int {
        var lit = 0
        for (c in codes) if ((c.toInt() and 0x03) != 1) lit++
        return (100f * lit / codes.size).roundToInt()
    }

    private fun fitAreaAverage(input: Bitmap, width: Int, height: Int, cover: Boolean): IntArray {
        val srcW = input.width
        val srcH = input.height
        val scale = if (cover) max(width / srcW.toFloat(), height / srcH.toFloat())
        else min(width / srcW.toFloat(), height / srcH.toFloat())

        if (scale >= 1f) {
            val fitted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fitted)
            canvas.drawColor(Color.WHITE)
            val w = srcW * scale
            val h = srcH * scale
            canvas.drawBitmap(
                input,
                null,
                RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            return IntArray(width * height).also { fitted.getPixels(it, 0, width, 0, 0, width, height) }
        }

        val src = IntArray(srcW * srcH).also { input.getPixels(it, 0, srcW, 0, 0, srcW, srcH) }
        val out = IntArray(width * height)
        Arrays.fill(out, Color.WHITE)

        val drawnW = srcW * scale
        val drawnH = srcH * scale
        val originX = (width - drawnW) / 2.0
        val originY = (height - drawnH) / 2.0

        for (y in 0 until height) {
            var top = (y - originY) / scale
            var bottom = (y + 1 - originY) / scale
            if (bottom <= 0 || top >= srcH) continue
            top = max(0.0, top)
            bottom = min(srcH.toDouble(), bottom)
            val firstRow = floor(top).toInt()
            val lastRow = ceil(bottom).toInt()

            for (x in 0 until width) {
                var left = (x - originX) / scale
                var right = (x + 1 - originX) / scale
                if (right <= 0 || left >= srcW) continue
                left = max(0.0, left)
                right = min(srcW.toDouble(), right)
                val firstCol = floor(left).toInt()
                val lastCol = ceil(right).toInt()

                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var weight = 0.0

                for (sy in firstRow until lastRow) {
                    val overlapY = min(bottom, sy + 1.0) - max(top, sy.toDouble())
                    if (overlapY <= 0) continue
                    val syBase = sy * srcW
                    for (sx in firstCol until lastCol) {
                        val overlapX = min(right, sx + 1.0) - max(left, sx.toDouble())
                        if (overlapX <= 0) continue
                        val area = overlapX * overlapY
                        val px = src[syBase + sx]
                        val alpha = ((px ushr 24) / 255.0) * area
                        val blank = area - alpha
                        sumR += ((px ushr 16) and 0xFF) * alpha + 255.0 * blank
                        sumG += ((px ushr 8) and 0xFF) * alpha + 255.0 * blank
                        sumB += (px and 0xFF) * alpha + 255.0 * blank
                        weight += area
                    }
                }
                if (weight <= 0) continue
                val r = (sumR / weight).roundToInt().coerceIn(0, 255)
                val g = (sumG / weight).roundToInt().coerceIn(0, 255)
                val b = (sumB / weight).roundToInt().coerceIn(0, 255)
                out[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
