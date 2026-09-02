package com.levelhard.cadentia.features.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.InstrumentDestination
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzTokens

/** Um instrumento no hub: arte cheia, nome e o que ele faz — port do `InstrumentCard`. */
data class InstrumentCard(
    val id: String,
    val destination: InstrumentDestination,
    val titleRes: Int,
    val detailRes: Int,
    val tagRes: List<Int>,
    /** A cor que a arte vetorial usa e o brilho por trás do card. */
    val tint: Color,
) {
    companion object {
        val piano = InstrumentCard(
            id = "piano", destination = InstrumentDestination.Piano,
            titleRes = R.string.music_tabs_piano, detailRes = R.string.cadentia_instruments_piano_detail,
            tagRes = listOf(R.string.cadentia_instruments_tag_keys),
            tint = Color(0xFF8B6CC4),
        )
        val drums = InstrumentCard(
            id = "drums", destination = InstrumentDestination.Drums,
            titleRes = R.string.music_tabs_drums, detailRes = R.string.cadentia_instruments_drums_detail,
            tagRes = listOf(R.string.cadentia_instruments_tag_percussion),
            tint = CzTokens.danger,
        )

        /** Cordas e Baixo entram na fase 8 (Kit do Cordas); até lá o hub é honesto. */
        val playable: List<InstrumentCard> = listOf(piano, drums)
    }
}

/**
 * Port do `InstrumentCardView`: o fundo com um fio da cor do instrumento, a
 * arte à direita morrendo num degradê antes de chegar no texto (não é um véu
 * escuro por cima: é a arte sumindo dentro do fundo), nome, detalhe e tags.
 */
@Composable
fun InstrumentCardView(card: InstrumentCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(CzTokens.radiusLG)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(154.dp)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(CzTokens.surface, CzTokens.stageBottom, card.tint.copy(alpha = 0.14f)),
                ),
            )
            .border(1.dp, CzTokens.hairline, shape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        // A arte ocupa os 62% da direita e some para a esquerda.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.38f))
            Box(Modifier.weight(0.62f).fillMaxHeight()) {
                InstrumentArt(card.id, card.tint, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0.0f to CzTokens.stageBottom.copy(alpha = 0.92f),
                                0.30f to CzTokens.stageBottom.copy(alpha = 0.72f),
                                0.58f to CzTokens.stageBottom.copy(alpha = 0.20f),
                                0.80f to Color.Transparent,
                            ),
                        ),
                )
            }
        }
        // Tags em cima, à esquerda.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 18.dp, top = 15.dp),
        ) {
            for (tag in card.tagRes) {
                Text(
                    text = stringResource(tag).uppercase(),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp,
                    color = CzTokens.textSecondary,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.10f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        // Nome e detalhe embaixo: o texto para ANTES de a arte ficar opaca.
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, end = 158.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(card.titleRes),
                fontSize = 23.sp,
                fontWeight = FontWeight.Black,
                color = CzTokens.textPrimary,
            )
            Text(
                text = stringResource(card.detailRes),
                fontSize = 12.5.sp,
                color = CzTokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A arte de um instrumento — port do `InstrumentArt`: desenho por geometria,
 * assumidamente gráfico (realismo malfeito ficaria pior). A composição é a
 * mesma que uma foto teria, para uma trocar a outra sem mexer no layout.
 */
@Composable
fun InstrumentArt(id: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(
            Brush.linearGradient(
                listOf(tint.copy(alpha = 0.30f), CzTokens.stageBottom),
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height),
            ),
        )
        when (id) {
            "piano" -> pianoArt(tint)
            "drums" -> drumsArt(tint)
            "cordas" -> cordasArt(tint)
            else -> bassArt(tint)
        }
    }
}

/** Sete teclas inclinadas a -13°, três roxas, cinco pretas por cima. */
private fun DrawScope.pianoArt(tint: Color) {
    val keyW = 22.dp.toPx()
    val gap = 2.dp.toPx()
    val keyH = 116.dp.toPx()
    val totalW = 7 * keyW + 6 * gap
    val left = size.width - totalW + 16.dp.toPx()
    val top = (size.height - keyH) / 2 - 6.dp.toPx()
    rotate(degrees = -13f, pivot = Offset(left + totalW / 2, top + keyH / 2)) {
        for (i in 0 until 7) {
            val x = left + i * (keyW + gap)
            drawRoundRect(
                color = if (i % 3 == 0) tint.copy(alpha = 0.85f) else Color(0xFFE6E6E6),
                topLeft = Offset(x, top),
                size = Size(keyW, keyH),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            if (i in listOf(0, 1, 3, 4, 5)) {
                drawRoundRect(
                    color = Color(0xFF0D0D0D),
                    topLeft = Offset(x + 15.dp.toPx(), top),
                    size = Size(12.dp.toPx(), 74.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

/** Bumbo, dois pratos dourados e uma caixa — a bateria vista de frente. */
private fun DrawScope.drumsArt(tint: Color) {
    val cx = size.width - 60.dp.toPx()
    val cy = size.height / 2
    drawCircle(Color(0xFF1A1A1A), radius = 52.dp.toPx(), center = Offset(cx, cy + 14.dp.toPx()))
    drawCircle(tint.copy(alpha = 0.55f), radius = 48.dp.toPx(), center = Offset(cx, cy + 14.dp.toPx()), style = Stroke(1.6.dp.toPx()))
    drawOval(CzTokens.gold.copy(alpha = 0.75f), topLeft = Offset(cx - 62.dp.toPx() - 31.dp.toPx(), cy - 26.dp.toPx() - 10.dp.toPx()), size = Size(62.dp.toPx(), 20.dp.toPx()))
    drawOval(CzTokens.gold.copy(alpha = 0.62f), topLeft = Offset(cx + 58.dp.toPx() - 25.dp.toPx(), cy - 36.dp.toPx() - 8.5.dp.toPx()), size = Size(50.dp.toPx(), 17.dp.toPx()))
    drawCircle(Color(0xFF242424), radius = 24.dp.toPx(), center = Offset(cx - 34.dp.toPx(), cy - 50.dp.toPx()))
}

/** A boca do violão e seis cordas correndo para a esquerda. */
private fun DrawScope.cordasArt(tint: Color) {
    val cx = size.width - 64.dp.toPx() + 6.dp.toPx()
    val cy = size.height / 2
    drawCircle(tint.copy(alpha = 0.35f), radius = 64.dp.toPx(), center = Offset(cx, cy), style = Stroke(22.dp.toPx()))
    drawCircle(tint.copy(alpha = 0.55f), radius = 23.dp.toPx(), center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
    val gap = 8.dp.toPx()
    for (i in 0 until 6) {
        val y = cy - 2.5f * gap + i * gap
        drawLine(tint.copy(alpha = 0.6f), Offset(cx - 46.dp.toPx() - 95.dp.toPx(), y), Offset(cx - 46.dp.toPx() + 95.dp.toPx(), y), strokeWidth = 1.dp.toPx())
    }
}

/** Quatro cordas grossas, só isso: o baixo é o braço. */
private fun DrawScope.bassArt(tint: Color) {
    val right = size.width - 22.dp.toPx()
    val cy = size.height / 2
    val gap = 12.4.dp.toPx()
    for (i in 0 until 4) {
        val y = cy - 1.5f * gap + i * gap
        drawLine(tint.copy(alpha = 0.35f), Offset(right - 172.dp.toPx(), y), Offset(right, y), strokeWidth = 1.4.dp.toPx())
    }
}
