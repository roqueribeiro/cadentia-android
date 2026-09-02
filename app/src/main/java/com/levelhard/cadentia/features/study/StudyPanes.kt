package com.levelhard.cadentia.features.study

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.I18nMap
import com.levelhard.cadentia.R
import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.Chord
import com.levelhard.cadentia.kit.ChordLibrary
import com.levelhard.cadentia.kit.InstrumentSynth
import com.levelhard.cadentia.kit.InstrumentVoice
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.ScaleType
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/*
 * Acordes e Escalas — port do `StudyPanes.swift` (1.16). Nasceram como modos
 * do Piano e nunca foram do piano: a tela de acordes desenha um braço de
 * violão. Saíram de lá inteiros, sem mudar um número; o seletor de
 * instrumento (piano × violão × guitarra × viola × baixo) chega com o Kit do
 * Cordas na fase 8.
 */

// MARK: acordes

@Composable
internal fun ChordsPane(
    store: SettingsStore,
    accent: Color,
    play: (Double, Double) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val settings by store.settings.collectAsState()
    val chord = ChordLibrary.find(settings.piano.chordRoot, settings.piano.chordQuality)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (root in ChordLibrary.roots) {
            PillChip(root, settings.piano.chordRoot == root, accent) { // i18n-verbatim: nota
                store.update { it.piano.chordRoot = root }
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (quality in ChordLibrary.qualities) {
            PillChip(quality.label, settings.piano.chordQuality == quality.id, accent) { // i18n-verbatim: cifra
                store.update { it.piano.chordQuality = quality.id }
            }
        }
    }

    if (chord != null) {
        CzCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(18.dp),
            ) {
                Text(
                    text = chord.displayName, // i18n-verbatim: cifra
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textPrimary,
                )
                Text(
                    text = chord.notes.joinToString(" · "), // i18n-verbatim: notas
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                )
                GuitarChordDiagram(
                    frets = chord.guitarFrets,
                    accent = accent,
                    modifier = Modifier.size(width = 190.dp, height = 200.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionPill(
                        stringResource(R.string.music_chords_strum),
                        Icons.Filled.PlayArrow, accent, prominent = true,
                    ) { playChord(chord, arpeggio = false, play, scope) }
                    ActionPill(
                        stringResource(R.string.music_chords_arpeggio),
                        Icons.Filled.GraphicEq, accent, prominent = false,
                    ) { playChord(chord, arpeggio = true, play, scope) }
                }
            }
        }
    }
}

internal fun playChord(
    chord: Chord,
    arpeggio: Boolean,
    play: (Double, Double) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val frequencies = chord.pianoNotes.mapNotNull { note ->
        val octave = note.last().digitToIntOrNull() ?: return@mapNotNull null
        MusicNotes.frequency(note.dropLast(1), octave)
    }
    for ((index, frequency) in frequencies.withIndex()) {
        val delayS = if (arpeggio) index * 0.18 else index * 0.012
        scope.launch {
            delay((delayS * 1000).toLong())
            play(frequency, if (arpeggio) 0.8 else 1.4)
        }
    }
}

/**
 * Caixa clássica de acorde: 6 cordas × janela de 5 casas, bolinhas nos
 * dedos, ✕/○ sobre a pestana, número da casa quando a forma sobe o braço.
 */
@Composable
internal fun GuitarChordDiagram(frets: List<Int>, accent: Color, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val stringCount = 6
        val fretCount = 5
        val inset = 22.dp.toPx()
        val gridWidth = size.width - inset * 2
        val gridHeight = size.height - inset * 2
        val stringGap = gridWidth / (stringCount - 1)
        val fretGap = gridHeight / fretCount

        val positive = frets.filter { it > 0 }
        val baseFret = if ((positive.maxOrNull() ?: 0) > 4) (positive.minOrNull() ?: 1) else 1

        for (s in 0 until stringCount) {
            val x = inset + s * stringGap
            drawLine(
                Color.White.copy(alpha = 0.35f),
                Offset(x, inset), Offset(x, inset + gridHeight),
                strokeWidth = 1.dp.toPx(),
            )
        }
        for (f in 0..fretCount) {
            val y = inset + f * fretGap
            val isNut = f == 0 && baseFret == 1
            drawLine(
                Color.White.copy(alpha = if (isNut) 0.9f else 0.35f),
                Offset(inset, y), Offset(inset + gridWidth, y),
                strokeWidth = (if (isNut) 3.dp else 1.dp).toPx(),
            )
        }
        if (baseFret > 1) {
            val label = textMeasurer.measure(
                "${baseFret}ª", // i18n-verbatim: número ordinal de casa
                TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            )
            drawText(
                label,
                color = Color.White.copy(alpha = 0.6f),
                topLeft = Offset(inset - 13.dp.toPx() - label.size.width / 2f, inset + fretGap / 2 - label.size.height / 2f),
            )
        }

        for ((string, fret) in frets.withIndex()) {
            val x = inset + string * stringGap
            when {
                fret < 0 -> {
                    val cross = textMeasurer.measure(
                        "✕",
                        TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    )
                    drawText(
                        cross,
                        color = Color.White.copy(alpha = 0.5f),
                        topLeft = Offset(x - cross.size.width / 2f, inset - 12.dp.toPx() - cross.size.height / 2f),
                    )
                }
                fret == 0 -> {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.7f),
                        radius = 4.5.dp.toPx(),
                        center = Offset(x, inset - 11.5.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                    )
                }
                else -> {
                    val row = fret - baseFret
                    if (row in 0 until fretCount) {
                        drawCircle(
                            color = accent,
                            radius = 7.dp.toPx(),
                            center = Offset(x, inset + (row + 0.5f) * fretGap),
                        )
                    }
                }
            }
        }
    }
}

// MARK: escalas

@Composable
internal fun ScalesPane(
    store: SettingsStore,
    accent: Color,
    play: (Double, Double) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val settings by store.settings.collectAsState()
    val scale = ScaleType.find(settings.piano.scaleType)
    val notes = scale.notesWithFrequency(settings.piano.scaleRoot, octaveBase = 4)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (root in MusicNotes.noteNames) {
            PillChip(root, settings.piano.scaleRoot == root, accent) { // i18n-verbatim: nota
                store.update { it.piano.scaleRoot = root }
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (type in ScaleType.all) {
            PillChip(
                stringResource(I18nMap.res(type.nameKey)),
                settings.piano.scaleType == type.id,
                accent,
            ) {
                store.update { it.piano.scaleType = type.id }
            }
        }
    }

    // Notas: toque para ouvir.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (note in notes) {
            val isRoot = note.name == settings.piano.scaleRoot
            Surface(
                onClick = { play(note.frequency, 0.7) },
                shape = CircleShape,
                color = if (isRoot) accent else CzTokens.surface,
                contentColor = if (isRoot) CzTokens.stageBottom else CzTokens.textPrimary,
                border = BorderStroke(1.dp, CzTokens.hairline),
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = note.name, // i18n-verbatim: nota
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    ScaleFretboard(
        scaleNotes = scale.notes(settings.piano.scaleRoot),
        root = settings.piano.scaleRoot,
        accent = accent,
        modifier = Modifier.fillMaxWidth().height(184.dp),
    )

    ActionPill(
        stringResource(R.string.music_scales_play_scale),
        Icons.Filled.PlayArrow, accent, prominent = true,
    ) {
        val sequence = notes.toMutableList()
        MusicNotes.noteToMidi(settings.piano.scaleRoot, 4)?.let { rootMidi ->
            val octaveUp = rootMidi + 12
            sequence.add(
                ScaleType.ScaleNote(
                    settings.piano.scaleRoot, 5, octaveUp, MusicNotes.midiToFrequency(octaveUp),
                ),
            )
        }
        for ((index, note) in sequence.withIndex()) {
            scope.launch {
                delay((index * 280).toLong())
                play(note.frequency, 0.5)
            }
        }
    }
}

/**
 * Mapa de 12 casas × 6 cordas da escala: bolinha em todo grau, tônica no
 * acento (o ScaleFretboard do web).
 */
@Composable
internal fun ScaleFretboard(
    scaleNotes: List<String>,
    root: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    // Afinação padrão, aguda EM CIMA como cifra impressa.
    val openStrings = listOf("E", "B", "G", "D", "A", "E")
    val openPitchClass = listOf(4, 11, 7, 2, 9, 4)

    Box(
        modifier
            .clip(RoundedCornerShape(CzTokens.radiusMD))
            .background(Color.Black.copy(alpha = 0.25f)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strings = 6
            val frets = 12
            val insetX = 38.dp.toPx()
            val labelRight = 17.dp.toPx()
            val openDotX = 27.dp.toPx()
            val insetY = 12.dp.toPx()
            // Faixa própria para os números das casas, ABAIXO da última corda:
            // desenhados na borda do canvas eles ficavam por baixo das bolinhas
            // do mi grave (visto no QA do emulador).
            val markerBand = 14.dp.toPx()
            val gridWidth = size.width - insetX - 10.dp.toPx()
            val gridHeight = size.height - insetY * 2 - markerBand
            val stringGap = gridHeight / (strings - 1)
            val fretGap = gridWidth / frets

            for (s in 0 until strings) {
                val y = insetY + s * stringGap
                drawLine(
                    Color.White.copy(alpha = 0.3f),
                    Offset(insetX, y), Offset(insetX + gridWidth, y),
                    strokeWidth = (if (s < 3) 1.dp else 1.6.dp).toPx(),
                )
                val label = textMeasurer.measure(
                    openStrings[s],
                    TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                )
                drawText(
                    label,
                    color = Color.White.copy(alpha = 0.5f),
                    topLeft = Offset(labelRight - label.size.width, y - label.size.height / 2f),
                )
            }
            for (f in 0..frets) {
                val x = insetX + f * fretGap
                drawLine(
                    Color.White.copy(alpha = if (f == 0) 0.8f else 0.2f),
                    Offset(x, insetY), Offset(x, insetY + gridHeight),
                    strokeWidth = (if (f == 0) 2.5.dp else 1.dp).toPx(),
                )
            }
            for (marker in listOf(3, 5, 7, 9, 12)) {
                val label = textMeasurer.measure(
                    "$marker",
                    TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                )
                drawText(
                    label,
                    color = Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(
                        insetX + (marker - 0.5f) * fretGap - label.size.width / 2f,
                        insetY + gridHeight + 3.dp.toPx(),
                    ),
                )
            }

            for (s in 0 until strings) {
                for (f in 0..frets) {
                    val pitchClass = (openPitchClass[s] + f) % 12
                    val name = MusicNotes.noteNames[pitchClass]
                    if (name !in scaleNotes) continue
                    val x = if (f == 0) openDotX else insetX + (f - 0.5f) * fretGap
                    val y = insetY + s * stringGap
                    val isRoot = name == root
                    drawCircle(
                        color = if (isRoot) accent else Color.White.copy(alpha = 0.75f),
                        radius = (if (isRoot) 6.5.dp else 5.dp).toPx(),
                        center = Offset(x, y),
                    )
                }
            }
        }
    }
}

// MARK: compartilhados

@Composable
internal fun PillChip(text: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) accent.copy(alpha = 0.18f) else CzTokens.surface,
        contentColor = if (active) accent else CzTokens.textSecondary,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
internal fun ActionPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    prominent: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (prominent) accent else CzTokens.surface,
        contentColor = if (prominent) CzTokens.stageBottom else CzTokens.textPrimary,
        border = if (prominent) null else BorderStroke(1.dp, CzTokens.hairline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
