package com.inkpaper.epd.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Nearest-neighbor 1:1 E-Ink pixel inspection viewer with pinch zoom and double-tap toggle. */
@Composable
fun PixelZoomDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    BackHandler(onBack = onDismiss)
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var left by remember { mutableFloatStateOf(0f) }
    var top by remember { mutableFloatStateOf(0f) }

    fun fitScale(): Float =
        if (viewW > 0f && viewH > 0f) min(viewW / bitmap.width, viewH / bitmap.height) else 1f

    fun clampAndCenter(targetScale: Float, curLeft: Float, curTop: Float): Triple<Float, Float, Float> {
        val fs = fitScale()
        val s = targetScale.coerceIn(min(1f, fs), max(8f, fs * 8f))
        val w = bitmap.width * s
        val h = bitmap.height * s
        val l = if (w <= viewW) (viewW - w) / 2f else curLeft.coerceIn(viewW - w, 0f)
        val t = if (h <= viewH) (viewH - h) / 2f else curTop.coerceIn(viewH - h, 0f)
        return Triple(s, l, t)
    }

    fun applyCentered(targetScale: Float) {
        val l = (viewW - bitmap.width * targetScale) / 2f
        val t = (viewH - bitmap.height * targetScale) / 2f
        val (s, cl, ct) = clampAndCenter(targetScale, l, t)
        scale = s
        left = cl
        top = ct
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF151C18))
            .onSizeChanged { sz ->
                viewW = sz.width.toFloat()
                viewH = sz.height.toFloat()
                applyCentered(fitScale())
            }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val fs = fitScale()
                        if (scale > fs * 1.2f) {
                            applyCentered(fs)
                        } else {
                            val next = fs * 2.2f
                            val ratio = next / scale
                            val (s, cl, ct) = clampAndCenter(
                                next,
                                tap.x - (tap.x - left) * ratio,
                                tap.y - (tap.y - top) * ratio,
                            )
                            scale = s
                            left = cl
                            top = ct
                        }
                    },
                )
            }
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val fs = fitScale()
                    val next = (scale * zoom).coerceIn(min(1f, fs), max(8f, fs * 8f))
                    val ratio = if (scale > 0f) next / scale else 1f
                    val nextLeft = centroid.x - (centroid.x - left) * ratio + pan.x
                    val nextTop = centroid.y - (centroid.y - top) * ratio + pan.y
                    val (s, cl, ct) = clampAndCenter(next, nextLeft, nextTop)
                    scale = s
                    left = cl
                    top = ct
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawImage(
                image = image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.None,
            )
        }

        Text(
            text = "放大预览 ${bitmap.width}×${bitmap.height} · 双指缩放或双击",
            color = Color(0xFFD5DDD8),
            fontSize = 12.sp,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xA60B0F0D))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = { applyCentered(fitScale()) },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("全屏适应") }
            FilledTonalButton(
                onClick = { applyCentered(fitScale() * 2f) },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("放大 2×") }
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("关闭") }
        }
    }
}
