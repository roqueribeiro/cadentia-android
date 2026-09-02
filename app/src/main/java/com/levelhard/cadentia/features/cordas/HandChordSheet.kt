package com.levelhard.cadentia.features.cordas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.cordas.HandChordMapping
import com.levelhard.cadentia.kit.cordas.TwoHandChords
import com.levelhard.cadentia.ui.CzTokens

/**
 * Qual acorde cada gesto da mão esquerda toca — port do `HandChordSheet.swift`
 * (1.16).
 *
 * A lista era fixa e servia para música em Sol e em Dó; quem fosse tocar em Ré
 * tinha que aceitar o repertório que o app escolheu. A tela mostra **o gesto**,
 * não o número de dedos: os quatro pontinhos são o desenho da mão, na mesma
 * ordem da faixa de baixo da câmera. Com a mão direita livre do ritmo, as duas
 * mãos CONTAM: uma seção por contagem da direita, cada linha por extenso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandChordSheet(model: CordasModel, accent: Color, onDismiss: () -> Unit) {
    val fingerNames = listOf(
        stringResource(R.string.cadentia_cordas_finger_index),
        stringResource(R.string.cadentia_cordas_finger_middle),
        stringResource(R.string.cadentia_cordas_finger_ring),
        stringResource(R.string.cadentia_cordas_finger_little),
    )
    val fist = stringResource(R.string.cadentia_cordas_finger_fist)
    val none = stringResource(R.string.cadentia_cordas_hand_chords_none)
    val pairFormat = stringResource(R.string.cadentia_cordas_hand_chords_pair)
    val rightCountFormat = stringResource(R.string.cadentia_cordas_hand_chords_right_count)
    val playable = model.playableChordNames
    val counting = model.countsChords

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp)
                .testTag("cordas.handChords"),
        ) {
            item {
                Text(
                    text = stringResource(R.string.cadentia_cordas_hand_chords_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            if (counting) {
                for (right in 0 until TwoHandChords.COUNTS) {
                    item(key = "header-$right") {
                        SectionHeader(String.format(java.util.Locale.getDefault(), rightCountFormat, right))
                    }
                    items((0 until TwoHandChords.COUNTS).toList(), key = { "pair-$it-$right" }) { left ->
                        val current = model.twoHandGrid.chord(left, right) ?: ""
                        ChordRow(
                            mask = (1 shl left) - 1,
                            label = String.format(java.util.Locale.getDefault(), pairFormat, left, right),
                            value = current.ifEmpty { none },
                            options = listOf("" to none) + playable.map { it to it },
                            selected = current,
                            accent = accent,
                            tag = "cordas.handChords.$left.$right",
                        ) { model.setTwoHandChord(it, left, right) }
                    }
                    if (right == 0) {
                        item(key = "footer-counting") { Footer(stringResource(R.string.cadentia_cordas_hand_chords_counting_footer)) }
                    }
                }
            } else {
                item(key = "header-shapes") { SectionHeader(stringResource(R.string.cadentia_cordas_hand_chords_header)) }
                items(HandChordMapping.shapes.withIndex().toList(), key = { "shape-${it.index}" }) { (index, mask) ->
                    val names = model.chordNames
                    val current = names.getOrElse(index) { "" }
                    val text = HandChordMapping.label(mask, fingerNames).ifEmpty { fist }
                    ChordRow(
                        mask = mask, label = text, value = current,
                        options = playable.map { it to it }, selected = current, accent = accent,
                        tag = "cordas.handChords.$index",
                    ) { model.setHandChord(it, index) }
                }
                item(key = "footer-shapes") { Footer(stringResource(R.string.cadentia_cordas_hand_chords_footer)) }
            }
            item(key = "reset") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 28.dp)
                        .background(CzTokens.surface, RoundedCornerShape(12.dp))
                        .clickable { if (counting) model.resetTwoHandChords() else model.resetHandChords() }
                        .padding(vertical = 12.dp)
                        .testTag("cordas.handChords.reset"),
                ) {
                    Text(
                        text = stringResource(R.string.cadentia_cordas_hand_chords_reset),
                        color = CzTokens.danger,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.8.sp,
        color = CzTokens.textTertiary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = CzTokens.textTertiary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/**
 * Uma linha: o desenho da mão, o nome do gesto e o acorde escolhido, que abre
 * a lista. O desenho é o mesmo da faixa da câmera, de propósito.
 */
@Composable
private fun ChordRow(
    mask: Int,
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    selected: String,
    accent: Color,
    tag: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(CzTokens.surface, RoundedCornerShape(12.dp))
            .clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = "$label, $value" } // i18n-verbatim: junta o gesto e o acorde escolhidos
            .testTag(tag),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(52.dp)) {
            for (finger in 0 until 4) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (mask and (1 shl finger) != 0) accent else Color.White.copy(alpha = 0.12f), CircleShape),
                )
            }
        }
        Text(text = label, fontSize = 13.sp, color = CzTokens.textSecondary, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = CzTokens.textTertiary, modifier = Modifier.size(14.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((id, name) in options) {
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = {
                            if (id == selected) Icon(Icons.Filled.Check, contentDescription = null, tint = accent)
                        },
                        onClick = {
                            onPick(id)
                            open = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.size(0.dp))
    }
}
