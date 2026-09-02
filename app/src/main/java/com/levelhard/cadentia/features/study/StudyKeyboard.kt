package com.levelhard.cadentia.features.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.PianoVoicing
import com.levelhard.cadentia.ui.CzTokens

/**
 * Um teclado que não toca — só mostra onde as notas caem. Port do
 * `StudyKeyboard.swift` (1.16).
 *
 * Existe porque a ferramenta de acordes desenhava um braço de violão e nada
 * mais: quem estudava ao piano via a forma no violão e tinha que traduzir de
 * cabeça. Duas oitavas, que é o que cabe na largura de um telefone com a
 * tecla ainda legível, e o suficiente para qualquer acorde da biblioteca (com
 * o [PianoVoicing] encaixando o que nasce na oitava 3).
 *
 * A tecla é a mesma do teclado principal: mesmo gradiente, mesma borda,
 * mesmo brilho na preta. Os detalhes vêm menores porque a tecla vem menor. E
 * o grave fica à esquerda em qualquer idioma: instrumento não espelha.
 */
@Composable
fun StudyKeyboard(
    /** Notas em destaque, com oitava ("C4") ou sem ("C"): sem oitava, a classe de altura nas duas. */
    highlighted: List<String>,
    accent: Color,
    /** A tônica ganha um ponto: sem ela, um acorde e a sua inversão desenham o mesmo teclado. */
    root: String? = null,
    modifier: Modifier = Modifier,
) {
    val marks = remember(highlighted, root) { resolve(highlighted, root) }
    Canvas(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .height(108.dp)
            .shadow(8.dp, RoundedCornerShape(3.dp), clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(3.dp))
            .semantics { invisibleToUser() }
            .testTag("study.keyboard"),
    ) {
        val whiteCount = WHITE_PITCHES.size * OCTAVES
        val unit = size.width / whiteCount
        val blackWidth = unit * BLACK_WIDTH_RATIO
        val blackHeight = size.height * BLACK_HEIGHT_RATIO

        for (index in 0 until whiteCount) {
            val on = index in marks.white
            val left = index * unit
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    if (on) listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.75f)) else listOf(Color(0xFFFAFAFA), Color(0xFFE6E6E6)),
                    startX = left, endX = left + unit,
                ),
                topLeft = Offset(left + 0.5f, 0f),
                size = Size(unit - 1f, size.height),
                cornerRadius = CornerRadius(2.5.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xFF8C8C8C).copy(alpha = 0.9f),
                topLeft = Offset(left + 0.5f, 0f),
                size = Size(unit - 1f, size.height),
                cornerRadius = CornerRadius(2.5.dp.toPx()),
                style = Stroke(0.5.dp.toPx()),
            )
            if (index in marks.rootWhite) {
                drawCircle(CzTokens.stageBottom.copy(alpha = 0.75f), 2.5.dp.toPx(), Offset(left + unit / 2, size.height - 8.dp.toPx() - 2.5.dp.toPx()))
            }
        }
        for (index in 0 until BLACK_OFFSETS.size * OCTAVES) {
            val slot = BLACK_OFFSETS[index % BLACK_OFFSETS.size]
            val octave = index / BLACK_OFFSETS.size
            val base = octave * WHITE_PITCHES.size + slot.second
            val left = (base + 1) * unit - blackWidth / 2
            val on = index in marks.black
            drawRoundRect(
                brush = Brush.verticalGradient(
                    if (on) listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.7f)) else listOf(Color(0xFF292929), Color(0xFF0A0A0A)),
                    startY = 0f, endY = blackHeight,
                ),
                topLeft = Offset(left, 0f),
                size = Size(blackWidth, blackHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            // O brilho na ponta livre, como no teclado principal.
            drawRoundRect(
                color = Color.White.copy(alpha = if (on) 0.10f else 0.14f),
                topLeft = Offset(left + 1.dp.toPx(), blackHeight - 4.dp.toPx() - 1.dp.toPx()),
                size = Size(blackWidth - 2.dp.toPx(), 4.dp.toPx()),
                cornerRadius = CornerRadius(1.2.dp.toPx()),
            )
            if (index in marks.rootBlack) {
                drawCircle(Color.White.copy(alpha = 0.9f), 2.5.dp.toPx(), Offset(left + blackWidth / 2, blackHeight - 5.dp.toPx() - 2.5.dp.toPx()))
            }
        }
    }
}

private class KeyMarks(val white: Set<Int>, val black: Set<Int>, val rootWhite: Set<Int>, val rootBlack: Set<Int>)

private val WHITE_PITCHES = listOf(0, 2, 4, 5, 7, 9, 11)

/** (classe de altura, depois de qual branca da oitava). */
private val BLACK_OFFSETS = listOf(1 to 0, 3 to 1, 6 to 3, 8 to 4, 10 to 5)
private const val OCTAVES = 2

/** Os dois números vêm do `KeyboardLayout` do teclado principal, para as duas telas desenharem a mesma tecla. */
private const val BLACK_WIDTH_RATIO = 0.64f
private const val BLACK_HEIGHT_RATIO = 0.62f

/** Índices de teclas brancas e pretas a destacar, já resolvidos. */
private fun resolve(highlighted: List<String>, root: String?): KeyMarks {
    val white = HashSet<Int>()
    val black = HashSet<Int>()
    val rootWhite = HashSet<Int>()
    val rootBlack = HashSet<Int>()
    val rootPitch = root?.let { MusicNotes.pitchClass(it) }
    // O acorde inteiro deslocado para dentro das duas oitavas desenhadas (ver PianoVoicing).
    for (entry in PianoVoicing.fitted(highlighted, OCTAVES)) {
        val explicit = entry.lastOrNull()?.digitToIntOrNull()
        val name = if (explicit != null) entry.dropLast(1) else entry
        val pitch = MusicNotes.pitchClass(name) ?: continue
        // Com oitava, destaca só aquela; sem oitava, as duas.
        val range = if (explicit != null) listOf(explicit - 4) else (0 until OCTAVES).toList()
        for (octave in range) {
            if (octave < 0 || octave >= OCTAVES) continue
            val whiteIndex = WHITE_PITCHES.indexOf(pitch)
            if (whiteIndex >= 0) {
                val key = octave * WHITE_PITCHES.size + whiteIndex
                white += key
                if (pitch == rootPitch) rootWhite += key
            } else {
                val slot = BLACK_OFFSETS.indexOfFirst { it.first == pitch }
                if (slot >= 0) {
                    val key = octave * BLACK_OFFSETS.size + slot
                    black += key
                    if (pitch == rootPitch) rootBlack += key
                }
            }
        }
    }
    return KeyMarks(white, black, rootWhite, rootBlack)
}
