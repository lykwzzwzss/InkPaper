package com.inkpaper.epd.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkpaper.epd.R
import com.inkpaper.epd.imaging.ImageProcessor
import com.inkpaper.epd.protocol.PanelProtocol
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive 552:768 framing editor: fixed viewfinder frame, 1-finger pan + 2-finger pinch zoom.
 * Pinching out to contain-scale leaves intentional white margins; pinching in crops seamlessly.
 */
@Composable
fun CropOverlay(
    source: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    var rotationDegrees by remember(source) { mutableIntStateOf(0) }
    BackHandler(onBack = onCancel)
    val activeBitmap = remember(source, rotationDegrees) {
        ImageProcessor.rotate(source, rotationDegrees)
    }

    var canvasW by remember { mutableFloatStateOf(0f) }
    var canvasH by remember { mutableFloatStateOf(0f) }
    var imgLeft by remember { mutableFloatStateOf(0f) }
    var imgTop by remember { mutableFloatStateOf(0f) }
    var imgScale by remember { mutableFloatStateOf(1f) }
    var frameRect by remember { mutableStateOf(RectF()) }

    fun computeFrame(w: Float, h: Float): RectF {
        if (w <= 0f || h <= 0f) return RectF()
        val padH = 20f
        val topReserve = 120f
        val bottomReserve = 240f
        val availW = (w - padH * 2).coerceAtLeast(100f)
        val availH = (h - topReserve - bottomReserve).coerceAtLeast(100f)
        val ratio = PanelProtocol.HEIGHT.toFloat() / PanelProtocol.WIDTH.toFloat()
        var fw = availW
        var fh = fw * ratio
        if (fh > availH) {
            fh = availH
            fw = fh / ratio
        }
        val left = (w - fw) / 2f
        val top = topReserve + (availH - fh) / 2f
        return RectF(left, top, left + fw, top + fh)
    }

    fun clampTransform(frame: RectF, left: Float, top: Float, scale: Float, bmp: Bitmap): Triple<Float, Float, Float> {
        if (frame.isEmpty) return Triple(left, top, scale)
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val containScale = min(frame.width() / bw, frame.height() / bh)
        val coverScale = max(frame.width() / bw, frame.height() / bh)
        val s = scale.coerceIn(containScale, coverScale * 4f)
        val w = bw * s
        val h = bh * s
        val clampedLeft = if (w >= frame.width()) {
            left.coerceIn(frame.right - w, frame.left)
        } else {
            left.coerceIn(frame.left - w / 2f, frame.right - w / 2f)
        }
        val clampedTop = if (h >= frame.height()) {
            top.coerceIn(frame.bottom - h, frame.top)
        } else {
            top.coerceIn(frame.top - h / 2f, frame.bottom - h / 2f)
        }
        return Triple(clampedLeft, clampedTop, s)
    }

    fun resetFit(fillCover: Boolean, bmp: Bitmap = activeBitmap) {
        if (canvasW <= 0f || canvasH <= 0f) return
        val frame = computeFrame(canvasW, canvasH)
        frameRect = frame
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val s = if (fillCover) max(frame.width() / bw, frame.height() / bh)
        else min(frame.width() / bw, frame.height() / bh)
        val l = frame.centerX() - (bw * s) / 2f
        val t = frame.centerY() - (bh * s) / 2f
        val (cl, ct, cs) = clampTransform(frame, l, t, s, bmp)
        imgLeft = cl
        imgTop = ct
        imgScale = cs
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1311))
            .onSizeChanged { size ->
                canvasW = size.width.toFloat()
                canvasH = size.height.toFloat()
                resetFit(fillCover = true, bmp = activeBitmap)
            }
            .pointerInput(activeBitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (!frameRect.isEmpty && (zoom != 1f || pan != Offset.Zero)) {
                            val bw = activeBitmap.width.toFloat()
                            val bh = activeBitmap.height.toFloat()
                            val containScale = min(frameRect.width() / bw, frameRect.height() / bh)
                            val coverScale = max(frameRect.width() / bw, frameRect.height() / bh)
                            val nextScale = (imgScale * zoom).coerceIn(containScale, coverScale * 4f)
                            val actualZoom = if (imgScale > 0f) nextScale / imgScale else 1f
                            val focus = if (centroid != Offset.Unspecified) centroid else Offset(frameRect.centerX(), frameRect.centerY())
                            val nextLeft = focus.x - (focus.x - imgLeft) * actualZoom + pan.x
                            val nextTop = focus.y - (focus.y - imgTop) * actualZoom + pan.y
                            val (cl, ct, cs) = clampTransform(frameRect, nextLeft, nextTop, nextScale, activeBitmap)
                            imgLeft = cl
                            imgTop = ct
                            imgScale = cs
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val imageBitmap = remember(activeBitmap) { activeBitmap.asImageBitmap() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val frame = frameRect
            if (frame.isEmpty) return@Canvas

            val dstW = (activeBitmap.width * imgScale).roundToInt().coerceAtLeast(1)
            val dstH = (activeBitmap.height * imgScale).roundToInt().coerceAtLeast(1)

            clipRect(frame.left, frame.top, frame.right, frame.bottom) {
                drawRect(
                    color = Color.White,
                    topLeft = Offset(frame.left, frame.top),
                    size = Size(frame.width(), frame.height()),
                )
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
                    dstSize = IntSize(dstW, dstH),
                )
            }

            // Rule of thirds + crisp frame border
            val gridColor = Color(0x44FFFFFF)
            for (i in 1..2) {
                val gx = frame.left + frame.width() * i / 3f
                val gy = frame.top + frame.height() * i / 3f
                drawLine(gridColor, Offset(gx, frame.top), Offset(gx, frame.bottom), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(frame.left, gy), Offset(frame.right, gy), strokeWidth = 1.dp.toPx())
            }
            drawRect(
                color = Color(0xCCFFFFFF),
                topLeft = Offset(frame.left, frame.top),
                size = Size(frame.width(), frame.height()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            val tick = 20.dp.toPx()
            val stroke = 3.5.dp.toPx()
            drawLine(Color.White, Offset(frame.left, frame.top), Offset(frame.left + tick, frame.top), stroke)
            drawLine(Color.White, Offset(frame.left, frame.top), Offset(frame.left, frame.top + tick), stroke)
            drawLine(Color.White, Offset(frame.right - tick, frame.top), Offset(frame.right, frame.top), stroke)
            drawLine(Color.White, Offset(frame.right, frame.top), Offset(frame.right, frame.top + tick), stroke)
            drawLine(Color.White, Offset(frame.left, frame.bottom), Offset(frame.left + tick, frame.bottom), stroke)
            drawLine(Color.White, Offset(frame.left, frame.bottom - tick), Offset(frame.left, frame.bottom), stroke)
            drawLine(Color.White, Offset(frame.right - tick, frame.bottom), Offset(frame.right, frame.bottom), stroke)
            drawLine(Color.White, Offset(frame.right, frame.bottom - tick), Offset(frame.right, frame.bottom), stroke)
        }

        // Top subtle bar with instructions & quick layout helpers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "单指拖动 · 双指缩放",
                color = Color(0xFFD5DDD8),
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("完整") { resetFit(fillCover = false) }
                QuickChip("铺满") { resetFit(fillCover = true) }
                QuickChip("旋转", iconRes = R.drawable.ic_rotate) {
                    val nextRot = (rotationDegrees + 90) % 360
                    rotationDegrees = nextRot
                    resetFit(fillCover = true, bmp = ImageProcessor.rotate(source, nextRot))
                }
            }
        }

        // Bottom floating actions
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xB3080C0A))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF28302C),
                    contentColor = Color(0xFFD5E0DA),
                ),
            ) {
                Text("取消", fontSize = 15.sp)
            }
            Button(
                onClick = {
                    val out = renderCropOutput(activeBitmap, frameRect, imgLeft, imgTop, imgScale)
                    if (out != null) onConfirm(out) else onCancel()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF176B61),
                    contentColor = Color.White,
                ),
            ) {
                Text("使用构图", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun QuickChip(
    text: String,
    iconRes: Int? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color(0xFF232C28),
        contentColor = Color(0xFFE7EEEA),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.height(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(text, fontSize = 12.sp)
        }
    }
}

private fun renderCropOutput(
    bitmap: Bitmap,
    frame: RectF,
    imgLeft: Float,
    imgTop: Float,
    imgScale: Float,
): Bitmap? {
    if (frame.isEmpty) return null
    val out = Bitmap.createBitmap(PanelProtocol.WIDTH, PanelProtocol.HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(out)
    canvas.drawColor(AndroidColor.WHITE)
    canvas.save()
    canvas.scale(PanelProtocol.WIDTH / frame.width(), PanelProtocol.HEIGHT / frame.height())
    canvas.translate(-frame.left, -frame.top)
    val dst = RectF(
        imgLeft,
        imgTop,
        imgLeft + bitmap.width * imgScale,
        imgTop + bitmap.height * imgScale,
    )
    canvas.drawBitmap(bitmap, null, dst, AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG or AndroidPaint.ANTI_ALIAS_FLAG))
    canvas.restore()
    return out
}
