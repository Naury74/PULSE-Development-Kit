package com.naury.pulse.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val FULL_SWEEP = 360f
private const val START_ANGLE = -90f
private const val ANIMATION_MILLIS = 600
private const val SEGMENT_GAP_DEGREES = 3f

/**
 * 비율 하나를 고리로 보여 준다. 처음 나타날 때 0에서 [fraction]까지 차오른다.
 * 고리만으로 값을 전달하지 않도록 [content]에 숫자를 함께 넣는다.
 */
@Composable
internal fun RingGauge(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    stroke: Dp = 5.dp,
    content: @Composable () -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = remember { Animatable(0f) }
    LaunchedEffect(fraction) { progress.animateTo(fraction.coerceIn(0f, 1f), tween(ANIMATION_MILLIS)) }
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val width = stroke.toPx()
            val inset = width / 2
            val arcSize = Size(size.width - width, size.height - width)
            drawArc(track, 0f, FULL_SWEEP, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width))
            if (progress.value > 0f) {
                drawArc(
                    color,
                    START_ANGLE,
                    FULL_SWEEP * progress.value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** 여러 몫을 한 고리에 나눠 그린다. 몫 사이에 틈을 두어 색이 붙어 보이지 않게 한다. 합이 0이면 빈 고리. */
@Composable
internal fun DonutChart(
    segments: List<Pair<Int, Color>>,
    description: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 120.dp,
    stroke: Dp = 14.dp,
    center: @Composable () -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val total = segments.sumOf { it.first }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(segments) { progress.animateTo(1f, tween(ANIMATION_MILLIS)) }
    Box(modifier.size(diameter).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val width = stroke.toPx()
            val inset = width / 2
            val arcSize = Size(size.width - width, size.height - width)
            drawArc(track, 0f, FULL_SWEEP, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width))
            if (total == 0) return@Canvas
            val visible = segments.filter { it.first > 0 }
            val gap = if (visible.size > 1) SEGMENT_GAP_DEGREES else 0f
            var angle = START_ANGLE
            visible.forEach { (count, color) ->
                val sweep = FULL_SWEEP * count / total * progress.value
                drawArc(
                    color,
                    angle + gap / 2,
                    (sweep - gap).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width, cap = StrokeCap.Butt),
                )
                angle += sweep
            }
        }
        center()
    }
}

/** 화면 가운데 원형 로딩 표시와 짧은 안내. */
@Composable
internal fun PulseLoading(modifier: Modifier = Modifier, label: String = stringResource(R.string.pulse_loading)) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            strokeWidth = 4.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.s).clearAndSetSemantics { contentDescription = label },
        )
    }
}
