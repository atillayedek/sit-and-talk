package com.sitandtalk.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original mark: a speech bubble carrying three sound-wave bars. */
@Composable
fun SitAndTalkMark(modifier: Modifier = Modifier, size: Dp = 32.dp, bubble: Color = StColors.White, wave: Color = StColors.BlueDeep) {
    val description = stringResource(R.string.ds_logo_description)
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val w = this.size.width
        val h = this.size.height
        val bodyH = h * 0.78f
        drawRoundRect(bubble, topLeft = Offset.Zero, size = Size(w, bodyH), cornerRadius = CornerRadius(w * 0.28f, w * 0.28f))
        val tail = Path().apply {
            moveTo(w * 0.22f, bodyH - 1f)
            lineTo(w * 0.18f, h)
            lineTo(w * 0.44f, bodyH - 1f)
            close()
        }
        drawPath(tail, bubble)
        val bars = listOf(0.30f, 0.55f, 0.38f)
        val barWidth = w * 0.11f
        bars.forEachIndexed { i, frac ->
            val x = w * (0.30f + i * 0.20f)
            val half = bodyH * frac / 2f
            drawLine(wave, Offset(x, bodyH / 2 - half), Offset(x, bodyH / 2 + half), strokeWidth = barWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun SitAndTalkWordmark(modifier: Modifier = Modifier, color: Color = StColors.White, markBubble: Color = StColors.White, markWave: Color = StColors.BlueDeep, fontSize: TextUnit = 20.sp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SitAndTalkMark(size = (fontSize.value * 1.4f).dp, bubble = markBubble, wave = markWave)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ds_brand), color = color, fontWeight = FontWeight.Bold, fontSize = fontSize)
    }
}
