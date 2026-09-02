package com.levelhard.cadentia.features.tab

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.kit.TabRowDisplay
import com.levelhard.cadentia.kit.Tablature
import com.levelhard.cadentia.ui.CzTokens

/** Célula selecionada na grade (modo de edição). */
data class TabCellSelection(val row: Int, val col: Int)

/**
 * A pauta da tablatura — port do `TabGridView` do iOS: linhas horizontais
 * por corda/linha, número da casa (ou bolinha de bateria) por célula, barras
 * de compasso com selo de repetição, cifras em cima e a coluna do playhead.
 * Rola na horizontal e segue o cursor.
 *
 * Tablatura lê da esquerda para a direita em qualquer idioma (notação
 * musical), então a grade trava LTR mesmo com o app em árabe.
 */
@Composable
fun TabGridView(
    track: Tablature.Track,
    chordMarks: List<Tablature.ChordMark>,
    repeatBlocks: List<Tablature.RepeatBlock>,
    cursorColumn: Int?,
    selection: TabCellSelection?,
    accent: Color,
    revision: Int,
    modifier: Modifier = Modifier,
    onTapCell: ((row: Int, col: Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val columnWidth = with(density) { 26.dp.toPx() }
    val rowGap = with(density) { 24.dp.toPx() }
    val topBand = with(density) { 26.dp.toPx() }
    val inset = with(density) { 20.dp.toPx() }

    val rows = maxOf(track.rowCount, 1)
    val canvasWidthPx = track.totalColumns * columnWidth + 2 * inset
    val canvasHeightPx = (rows - 1) * rowGap + topBand + with(density) { 34.dp.toPx() }
    val canvasWidth = with(density) { canvasWidthPx.toDp() }
    val canvasHeight = with(density) { canvasHeightPx.toDp() }

    val scroll = rememberScrollState()
    var viewportWidth by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()

    // O cursor fica a 35% da largura: sobra passado à esquerda para se
    // situar e futuro à direita para se preparar — o que um músico lendo
    // precisa enxergar.
    LaunchedEffect(cursorColumn) {
        val column = cursorColumn ?: return@LaunchedEffect
        val target = (inset + column * columnWidth - viewportWidth * 0.35f).toInt().coerceAtLeast(0)
        scroll.animateScrollTo(target, tween(durationMillis = 120, easing = LinearEasing))
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .clip(RoundedCornerShape(CzTokens.radiusMD))
                .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(CzTokens.radiusMD))
                .onSizeChanged { viewportWidth = it.width }
                .horizontalScroll(scroll),
        ) {
            Canvas(
                modifier = Modifier
                    .width(canvasWidth)
                    .height(canvasHeight)
                    .pointerInput(onTapCell != null, revision) {
                        if (onTapCell == null) return@pointerInput
                        detectTapGestures { offset ->
                            val column = ((offset.x - inset) / columnWidth).toInt()
                            val row = ((offset.y - topBand + rowGap / 2) / rowGap).toInt()
                            if (offset.x >= inset && column in 0 until track.totalColumns &&
                                row in 0 until track.rowCount
                            ) {
                                onTapCell(row, column)
                            }
                        }
                    },
            ) {
                // `revision` força o redesenho quando o modelo muta no lugar.
                @Suppress("UNUSED_EXPRESSION") revision
                drawGrid(
                    track = track, chordMarks = chordMarks, repeatBlocks = repeatBlocks,
                    cursorColumn = cursorColumn, selection = selection, accent = accent,
                    rows = rows, inset = inset, columnWidth = columnWidth, rowGap = rowGap,
                    topBand = topBand, textMeasurer = textMeasurer,
                )
            }
        }
    }
}

private fun DrawScope.drawGrid(
    track: Tablature.Track,
    chordMarks: List<Tablature.ChordMark>,
    repeatBlocks: List<Tablature.RepeatBlock>,
    cursorColumn: Int?,
    selection: TabCellSelection?,
    accent: Color,
    rows: Int,
    inset: Float,
    columnWidth: Float,
    rowGap: Float,
    topBand: Float,
    textMeasurer: TextMeasurer,
) {
    fun rowY(row: Int): Float = topBand + row * rowGap
    // Cordas desenham com a aguda EM CIMA, como tablatura impressa e como o
    // ROSTab do site. O modelo é grave→aguda, então a tela inverte aqui (e
    // só aqui; o modelo nunca muda).
    fun displayRow(row: Int): Int = TabRowDisplay.displayRow(row, rows, track.type)
    fun colX(column: Int): Float = inset + (column + 0.5f) * columnWidth

    fun text(
        value: String, sizeSp: Int, weight: FontWeight, color: Color,
        centerX: Float, centerY: Float,
    ) {
        val layout = textMeasurer.measure(
            value,
            TextStyle(fontSize = sizeSp.sp, fontWeight = weight, color = color),
        )
        drawText(
            layout,
            topLeft = Offset(centerX - layout.size.width / 2f, centerY - layout.size.height / 2f),
        )
    }

    // Caixa de seleção (modo de edição).
    if (selection != null) {
        drawRoundRect(
            color = CzTokens.gold,
            topLeft = Offset(inset + selection.col * columnWidth + 2, rowY(displayRow(selection.row)) - 11.dp.toPx()),
            size = Size(columnWidth - 4, 22.dp.toPx()),
            cornerRadius = CornerRadius(5.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }

    // Coluna do playhead.
    if (cursorColumn != null) {
        drawRoundRect(
            color = accent.copy(alpha = 0.22f),
            topLeft = Offset(inset + cursorColumn * columnWidth, topBand - 12.dp.toPx()),
            size = Size(columnWidth, (rows - 1) * rowGap + 24.dp.toPx()),
            cornerRadius = CornerRadius(5.dp.toPx()),
        )
    }

    // Linhas das cordas + rótulos.
    for (row in 0 until rows) {
        drawLine(
            color = Color.White.copy(alpha = 0.22f),
            start = Offset(inset, rowY(row)),
            end = Offset(size.width - inset, rowY(row)),
            strokeWidth = 1.dp.toPx(),
        )
        val modelRow = displayRow(row)
        val label = track.rowsMeta.getOrNull(modelRow)?.label ?: ""
        if (label.isNotEmpty()) {
            text(label, 9, FontWeight.SemiBold, Color.White.copy(alpha = 0.45f), inset - 10.dp.toPx(), rowY(row))
        }
    }

    // Colchetes de bloco de repetição (estilo Guitar Pro: linha em cima com ×N).
    for (block in repeatBlocks) {
        if (block.startIdx >= track.measures.size) continue
        val startX = inset + track.measureStartColumn(block.startIdx) * columnWidth
        val endMeasure = minOf(block.endIdx, track.measures.size - 1)
        val endX = inset + (
            track.measureStartColumn(endMeasure) + track.measures[endMeasure].stepsPerMeasure
            ) * columnWidth
        val bracket = Path().apply {
            moveTo(startX, 6.dp.toPx())
            lineTo(startX, 1.dp.toPx())
            lineTo(endX, 1.dp.toPx())
            lineTo(endX, 6.dp.toPx())
        }
        drawPath(bracket, color = accent.copy(alpha = 0.8f), style = Stroke(width = 1.5.dp.toPx()))
        val label = if (block.count == -1) "∞" else "×${block.count}"
        text(label, 9, FontWeight.Bold, accent, (startX + endX) / 2, 7.dp.toPx())
    }

    // Compassos: barras, selos de repetição, células.
    var column = 0
    for ((measureIdx, measure) in track.measures.withIndex()) {
        val barX = inset + column * columnWidth
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = Offset(barX, topBand - 10.dp.toPx()),
            end = Offset(barX, rowY(rows - 1) + 10.dp.toPx()),
            strokeWidth = if (measureIdx == 0) 2.dp.toPx() else 1.dp.toPx(),
        )

        if (measure.repeats > 1 || measure.repeats == -1) {
            val label = if (measure.repeats == -1) "∞" else "×${measure.repeats}"
            text(label, 10, FontWeight.Bold, CzTokens.gold, barX + 14.dp.toPx(), topBand - 18.dp.toPx())
        }

        // Cifras acima deste compasso.
        for (mark in chordMarks) {
            if (mark.measureIdx != measureIdx) continue
            text(mark.displayName, 11, FontWeight.Bold, accent, colX(column + mark.col), 10.dp.toPx())
        }

        for (line in measure.strings) {
            for ((step, cell) in line.steps.withIndex()) {
                if (cell == null) continue
                val x = colX(column + step)
                val y = rowY(displayRow(line.stringIndex))
                val isDrums = track.type == "drums"
                val glyph = if (isDrums) "●" else "${cell.v}"
                val highlighted = cursorColumn == column + step
                // Recorta a linha da corda atrás do número.
                drawOval(
                    color = Color(0xFF0D0D14),
                    topLeft = Offset(x - 7.dp.toPx(), y - 7.dp.toPx()),
                    size = Size(14.dp.toPx(), 14.dp.toPx()),
                )
                text(
                    glyph, if (isDrums) 8 else 11, FontWeight.Bold,
                    if (highlighted) accent else CzTokens.textPrimary, x, y,
                )
                if (cell.articulations["pm"] == true) {
                    text("pm", 7, FontWeight.SemiBold, Color.White.copy(alpha = 0.5f), x, y - 12.dp.toPx())
                }
            }
        }
        column += measure.stepsPerMeasure
    }

    // Barra final.
    val endX = inset + column * columnWidth
    drawLine(
        color = Color.White.copy(alpha = 0.5f),
        start = Offset(endX, topBand - 10.dp.toPx()),
        end = Offset(endX, rowY(rows - 1) + 10.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
    )
}
