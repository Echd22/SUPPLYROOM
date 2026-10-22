package com.supplyroom.app

import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

class SigState {
    val strokes = mutableStateListOf<List<Offset>>()
    val current = mutableStateListOf<Offset>()
    var size: IntSize = IntSize.Zero

    val isEmpty: Boolean get() = strokes.isEmpty() && current.isEmpty()
    fun clear() { strokes.clear(); current.clear() }

    /** PNG data URL of the signature on a white background. */
    fun toDataUrl(): String {
        val w = size.width.coerceAtLeast(10)
        val h = size.height.coerceAtLeast(10)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val p = Paint().apply {
            color = android.graphics.Color.BLACK; strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND; isAntiAlias = true
        }
        strokes.forEach { s -> for (i in 1 until s.size) c.drawLine(s[i - 1].x, s[i - 1].y, s[i].x, s[i].y, p) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

@Composable
fun SignaturePad(state: SigState, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
            .background(Color.White, RoundedCornerShape(8.dp))
            .onSizeChanged { state.size = it }
            .pointerInput(Unit) {
                // Consume every touch so the page doesn't scroll while signing.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    state.current.clear()
                    state.current.add(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        state.current.add(ch.position)
                        ch.consume()
                    }
                    if (state.current.isNotEmpty()) state.strokes.add(state.current.toList())
                    state.current.clear()
                }
            },
    ) {
        val all = state.strokes + listOf(state.current.toList())
        all.forEach { s ->
            if (s.size == 1) drawCircle(Color.Black, 2.5f, s[0])
            for (i in 1 until s.size) drawLine(Color.Black, s[i - 1], s[i], strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
}
