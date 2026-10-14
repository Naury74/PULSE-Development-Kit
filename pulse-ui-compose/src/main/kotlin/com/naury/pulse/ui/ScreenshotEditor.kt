package com.naury.pulse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 편집 도구. [MASK]는 그 영역의 픽셀을 검은색으로 덮어써 원래 내용을 되돌릴 수 없게 한다. */
internal enum class EditTool(val label: Int) {
    MASK(R.string.pulse_editor_mask),
    BOX(R.string.pulse_editor_box),
    ARROW(R.string.pulse_editor_arrow),
    PEN(R.string.pulse_editor_pen),
    NUMBER(R.string.pulse_editor_number),
}

/** 편집 한 번. 좌표는 원본 이미지 기준 비율(0..1)이다. */
internal sealed interface Mark {
    class Area(val tool: EditTool, val start: Offset, val end: Offset) : Mark

    class Stroke(val points: List<Offset>) : Mark

    class Number(val at: Offset, val value: Int) : Mark
}

/**
 * 화면 캡처 편집기. 가리기·사각형·화살표·펜·번호를 그리고, 완료하면 그린 내용을 픽셀에 합친 새 PNG를 돌려준다.
 * 원본 PNG는 이 화면의 메모리에만 있고 저장하지 않는다.
 */
@Composable
internal fun ScreenshotEditor(png: ByteArray, onDone: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val source = remember(png) { BitmapFactory.decodeByteArray(png, 0, png.size) } ?: return onDismiss()
    val marks = remember { mutableStateListOf<Mark>() }
    var tool by remember { mutableStateOf(EditTool.MASK) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column {
                EditorToolbar(tool, {
                    tool = it
                }, canUndo = marks.isNotEmpty(), onUndo = { marks.removeAt(marks.lastIndex) }, onDismiss = onDismiss) {
                    onDone(render(source, marks))
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(Spacing.s), contentAlignment = Alignment.Center) {
                    EditorSurface(source, tool, marks)
                }
                Text(
                    stringResource(R.string.pulse_editor_note),
                    color = Color.White.copy(alpha = NOTE_ALPHA),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.m),
                )
            }
        }
    }
}

/** 이미지 위에 편집을 그리는 면. 이미지 비율을 지켜 가운데에 놓는다. */
@Composable
private fun EditorSurface(source: Bitmap, tool: EditTool, marks: MutableList<Mark>) {
    var draft by remember { mutableStateOf<Mark?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val ratio = source.width.toFloat() / source.height
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onSizeChanged { canvasSize = fitted(it, ratio) }, contentAlignment = Alignment.Center) {
        val shown = with(density) { Modifier.size(canvasSize.width.toDp(), canvasSize.height.toDp()) }
        Box(Modifier.background(Color.DarkGray).then(if (canvasSize != IntSize.Zero) shown else Modifier)) {
            Image(source.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(tool) {
                        if (tool == EditTool.NUMBER) {
                            detectTapGestures { at -> marks += Mark.Number(normalize(at, size), marks.count { it is Mark.Number } + 1) }
                        } else {
                            detectDragGestures(
                                onDragStart = { at ->
                                    val point = normalize(at, size)
                                    draft = if (tool == EditTool.PEN) Mark.Stroke(listOf(point)) else Mark.Area(tool, point, point)
                                },
                                onDrag = { change, _ -> draft = draft?.extended(normalize(change.position, size)) },
                                onDragEnd = {
                                    draft?.let { marks += it }
                                    draft = null
                                },
                            )
                        }
                    },
            ) {
                (marks + listOfNotNull(draft)).forEach { drawMark(it) }
            }
        }
    }
}

private fun Mark.extended(point: Offset): Mark = when (this) {
    is Mark.Stroke -> Mark.Stroke(points + point)
    is Mark.Area -> Mark.Area(tool, start, point)
    is Mark.Number -> this
}

@Composable
private fun EditorToolbar(
    tool: EditTool,
    onTool: (EditTool) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pulse_cancel)) }
            TextButton(onClick = onUndo, enabled = canUndo) { Text(stringResource(R.string.pulse_editor_undo)) }
            Box(Modifier.weight(1f))
            Button(onClick = onDone) { Text(stringResource(R.string.pulse_editor_done)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            EditTool.entries.forEach { option ->
                FilterChip(
                    selected = tool == option,
                    onClick = { onTool(option) },
                    label = { Text(stringResource(option.label)) },
                    shape = MaterialTheme.shapes.small,
                    colors = dashboardChipColors(),
                    border = dashboardChipBorder(tool == option),
                )
            }
        }
    }
}

private fun fitted(container: IntSize, ratio: Float): IntSize {
    if (container.width == 0 || container.height == 0) return IntSize.Zero
    return if (container.width / container.height.toFloat() > ratio) {
        IntSize((container.height * ratio).toInt(), container.height)
    } else {
        IntSize(container.width, (container.width / ratio).toInt())
    }
}

private fun normalize(at: Offset, size: IntSize): Offset =
    Offset((at.x / size.width).coerceIn(0f, 1f), (at.y / size.height).coerceIn(0f, 1f))

private val AnnotationRed = Color(0xFFE5383B)

private fun DrawScope.drawMark(mark: Mark) {
    val stroke = Stroke(width = STROKE_RATIO * size.minDimension)
    fun point(offset: Offset) = Offset(offset.x * size.width, offset.y * size.height)
    when (mark) {
        is Mark.Area -> {
            val rect = Rect(point(mark.start), point(mark.end)).normalized()
            when (mark.tool) {
                EditTool.MASK -> drawRect(Color.Black, rect.topLeft, rect.size)
                EditTool.BOX -> drawRect(AnnotationRed, rect.topLeft, rect.size, style = stroke)
                else -> drawArrow(point(mark.start), point(mark.end), stroke.width)
            }
        }

        is Mark.Stroke -> mark.points.zipWithNext().forEach { (a, b) -> drawLine(AnnotationRed, point(a), point(b), stroke.width) }

        is Mark.Number -> {
            val radius = NUMBER_RATIO * size.minDimension
            drawCircle(AnnotationRed, radius, point(mark.at))
            drawContext.canvas.nativeCanvas.drawText(
                mark.value.toString(),
                point(mark.at).x,
                point(mark.at).y + radius * TEXT_BASELINE,
                Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = radius * TEXT_SIZE
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    isFakeBoldText = true
                },
            )
        }
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, width: Float) {
    drawLine(AnnotationRed, from, to, width)
    val angle = atan2(to.y - from.y, to.x - from.x)
    val head = width * ARROW_HEAD
    listOf(angle + ARROW_SPREAD, angle - ARROW_SPREAD).forEach { side ->
        drawLine(AnnotationRed, to, Offset(to.x - head * cos(side), to.y - head * sin(side)), width)
    }
}

private fun Rect.normalized(): Rect = Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

/** 원본 크기의 새 비트맵에 편집을 합쳐 PNG로 만든다. 가리기 영역의 원래 픽셀은 남지 않는다. */
internal fun render(source: Bitmap, marks: List<Mark>): ByteArray {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val drawer = MarkDrawer(android.graphics.Canvas(output), output.width.toFloat(), output.height.toFloat())
    marks.forEach(drawer::draw)
    return ByteArrayOutputStream().use { out ->
        output.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        output.recycle()
        out.toByteArray()
    }
}

/** 비율 좌표의 편집을 실제 픽셀 크기의 캔버스에 그린다. */
private class MarkDrawer(private val canvas: android.graphics.Canvas, private val width: Float, private val height: Float) {
    private val strokeWidth = STROKE_RATIO * minOf(width, height)
    private val fill = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }
    private val line = Paint().apply {
        color = AnnotationRed.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = this@MarkDrawer.strokeWidth
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint().apply {
        color = AnnotationRed.toArgb()
        isAntiAlias = true
    }
    private val text = Paint().apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }

    fun draw(mark: Mark) = when (mark) {
        is Mark.Area -> area(mark)
        is Mark.Stroke -> stroke(mark)
        is Mark.Number -> number(mark)
    }

    private fun area(mark: Mark.Area) {
        val left = x(minOf(mark.start.x, mark.end.x))
        val top = y(minOf(mark.start.y, mark.end.y))
        val right = x(maxOf(mark.start.x, mark.end.x))
        val bottom = y(maxOf(mark.start.y, mark.end.y))
        when (mark.tool) {
            EditTool.MASK -> canvas.drawRect(left, top, right, bottom, fill)
            EditTool.BOX -> canvas.drawRect(left, top, right, bottom, line)
            else -> arrow(x(mark.start.x), y(mark.start.y), x(mark.end.x), y(mark.end.y))
        }
    }

    private fun arrow(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        canvas.drawLine(fromX, fromY, toX, toY, line)
        val angle = atan2(toY - fromY, toX - fromX)
        val head = strokeWidth * ARROW_HEAD
        listOf(angle + ARROW_SPREAD, angle - ARROW_SPREAD).forEach { side ->
            canvas.drawLine(toX, toY, toX - head * cos(side), toY - head * sin(side), line)
        }
    }

    private fun stroke(mark: Mark.Stroke) {
        val path = Path()
        mark.points.firstOrNull()?.let { path.moveTo(x(it.x), y(it.y)) }
        mark.points.drop(1).forEach { path.lineTo(x(it.x), y(it.y)) }
        canvas.drawPath(path, line)
    }

    private fun number(mark: Mark.Number) {
        val radius = NUMBER_RATIO * minOf(width, height)
        canvas.drawCircle(x(mark.at.x), y(mark.at.y), radius, dot)
        text.textSize = radius * TEXT_SIZE
        canvas.drawText(mark.value.toString(), x(mark.at.x), y(mark.at.y) + radius * TEXT_BASELINE, text)
    }

    private fun x(value: Float) = value * width

    private fun y(value: Float) = value * height
}

private const val STROKE_RATIO = 0.008f
private const val NUMBER_RATIO = 0.03f
private const val TEXT_SIZE = 1.2f
private const val TEXT_BASELINE = 0.4f
private const val ARROW_HEAD = 5f
private const val ARROW_SPREAD = 0.5f
private const val NOTE_ALPHA = 0.7f
private const val PNG_QUALITY = 100
