package dev.phoneagent.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    fileName: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val lineStarts = remember(value) { sourceLineStarts(value) }
    val gutterWidth = (20 + lineStarts.size.toString().length.coerceAtLeast(2) * 9).dp
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val grammar = remember(fileName) { sourceGrammar(fileName) }
    var findOpen by remember(fileName) { mutableStateOf(false) }
    var query by remember(fileName) { mutableStateOf("") }
    var matchIndex by remember(fileName) { mutableIntStateOf(0) }
    val matches = remember(value, query) { sourceMatches(value, query) }
    val currentMatch = matchIndex.coerceAtMost((matches.size - 1).coerceAtLeast(0))
    val activeMatch = matches.getOrNull(currentMatch)
    val transformation = remember(grammar, colors, matches, query.length, currentMatch) {
        VisualTransformation { text ->
            val highlighted = buildAnnotatedString {
                append(text)
                if (text.length <= MaxHighlightedCharacters) {
                    grammar?.findAll(text.text)?.forEach { match ->
                        val style = when {
                            match.groups["comment"] != null -> SpanStyle(color = colors.onSurfaceVariant)
                            match.groups["string"] != null -> SpanStyle(color = colors.tertiary)
                            match.groups["number"] != null -> SpanStyle(color = colors.secondary)
                            else -> SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
                        }
                        addStyle(style, match.range.first, match.range.last + 1)
                    }
                }
                matches.forEachIndexed { index, offset ->
                    if (offset + query.length <= text.length) {
                        val selected = index == currentMatch
                        val style = SpanStyle(
                            color = if (selected) colors.inverseOnSurface else colors.onSecondaryContainer,
                            background = if (selected) colors.inverseSurface else colors.secondaryContainer,
                        )
                        addStyle(style, offset, offset + query.length)
                    }
                }
            }
            TransformedText(highlighted, OffsetMapping.Identity)
        }
    }
    LaunchedEffect(activeMatch) {
        layout?.let { result ->
            if (activeMatch != null && activeMatch <= result.layoutInput.text.length) {
                val line = result.getLineForOffset(activeMatch)
                scroll.scrollTo(result.getLineTop(line).toInt().coerceAtLeast(0))
            }
        }
    }
    val numberPaint = remember(colors) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.onSurfaceVariant.toArgb()
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }
    }
    Surface(
        modifier = modifier,
        color = colors.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column {
            if (findOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it.take(256)
                        matchIndex = 0
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    label = { Text("Find in file") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = when {
                            query.isEmpty() -> ""
                            matches.isEmpty() -> "No matches"
                            matches.size == MaxMatches -> (currentMatch + 1).toString() + " / " + MaxMatches + "+"
                            else -> (currentMatch + 1).toString() + " / " + matches.size
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(
                        onClick = { matchIndex = (currentMatch + matches.size - 1) % matches.size },
                        enabled = matches.isNotEmpty(),
                    ) { Text("Prev") }
                    TextButton(
                        onClick = { matchIndex = (currentMatch + 1) % matches.size },
                        enabled = matches.isNotEmpty(),
                    ) { Text("Next") }
                    TextButton(onClick = {
                        findOpen = false
                        query = ""
                    }) { Text("Close") }
                }
            } else {
                TextButton(
                    onClick = { findOpen = true },
                    modifier = Modifier.align(Alignment.End).padding(end = 6.dp),
                ) { Text("Find") }
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 144.dp, max = 560.dp)
                    .verticalScroll(scroll).padding(vertical = 12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Source editor for " + fileName
                    },
                    readOnly = readOnly,
                    textStyle = TextStyle(
                        color = colors.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    decorationBox = { field ->
                        Box(Modifier.fillMaxWidth()) {
                            Canvas(Modifier.matchParentSize()) {
                                val result = layout ?: return@Canvas
                                val divider = gutterWidth.toPx() - 7.dp.toPx()
                                drawLine(colors.outlineVariant, Offset(divider, 0f), Offset(divider, size.height))
                                numberPaint.textSize = 11.sp.toPx()
                                val first = result.getLineForVerticalPosition((scroll.value - 32.dp.toPx()).coerceAtLeast(0f))
                                val last = result.getLineForVerticalPosition(scroll.value + scroll.viewportSize + 32.dp.toPx())
                                for (line in first..last) {
                                    val logicalLine = lineStarts.binarySearch(result.getLineStart(line))
                                    if (logicalLine >= 0) {
                                        drawContext.canvas.nativeCanvas.drawText(
                                            (logicalLine + 1).toString(),
                                            divider - 6.dp.toPx(),
                                            result.getLineBaseline(line),
                                            numberPaint,
                                        )
                                    }
                                }
                            }
                            Box(Modifier.fillMaxWidth().padding(start = gutterWidth, end = 10.dp)) {
                                field()
                            }
                        }
                    },
                )
            }
        }
    }
}

private const val MaxHighlightedCharacters = 180_000
