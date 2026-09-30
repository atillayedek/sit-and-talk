package com.sitandtalk.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Slowly expanding rings behind the main action. Static rings when the user prefers reduced motion. */
@Composable
fun WaveRings(modifier: Modifier = Modifier, color: Color = Color.White, active: Boolean = true) {
    val reduced = rememberReducedMotion()
    val progress = if (reduced || !active) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition(label = "rings")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
            label = "ringProgress",
        ).value
    }
    Canvas(modifier) {
        val maxRadius = size.minDimension / 2f
        val minRadius = maxRadius * 0.62f
        for (i in 0 until 3) {
            val p = ((progress + i / 3f) % 1f)
            val radius = minRadius + (maxRadius - minRadius) * p
            drawCircle(color = color.copy(alpha = (1f - p) * 0.35f), radius = radius, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** The large white circular primary action of the Talk screen. */
@Composable
fun BigRoundAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 196.dp,
    subtitle: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.White,
        contentColor = StColors.BlueDeep,
        shadowElevation = 10.dp,
        modifier = modifier.size(size),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(
                text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = StColors.InkSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Ring showing how much of the server-decided call time is left. [fraction] is computed from the
 * server's start/end timestamps, never from a local timer alone.
 */
@Composable
fun CircularCountdown(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    progressColor: Color = Color.White,
    warningColor: Color = StColors.Coral,
    content: @Composable () -> Unit,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val color = if (clamped < 0.15f) warningColor else progressColor
    Box(modifier.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            drawArc(trackColor, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            drawArc(color, -90f, 360f * clamped, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Round call control with icon + label; state is communicated by icon and text, not only color. */
@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
    stateLabel: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = containerColor ?: if (active) Color.White else Color.White.copy(alpha = 0.18f),
            contentColor = contentColor ?: if (active) StColors.BlueDeep else Color.White,
            modifier = Modifier
                .size(60.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    if (stateLabel != null) stateDescription = stateLabel
                },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp)) }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
