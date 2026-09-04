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
import androidx.compose.material.icons.outlined.HelpOutline
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
import com.levelhard.cadentia.kit.cordas.CordaChords
import com.levelhard.cadentia.kit.cordas.CordaInstrument
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
    /** `null` = piano. O acorde é o mesmo; muda só onde ele é desenhado. */
    instrument: CordaInstrument? = null,
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
                // O mesmo acorde, no instrumento escolhido. As formas de corda
                // saem de `CordaChords`, não de `chord.guitarFrets`: a viola
                // caipira não usa a mesma afinação, e uma forma de violão num
                // braço de viola é uma forma ERRADA — pior que nenhuma, porque
                // parece certa. No baixo, a CAIXA de arpejo, não a fundamental
                // sozinha: um ponto e três cruzes não ensinam nada.
                if (instrument == null) {
                    StudyKeyboard(highlighted = chord.pianoNotes, accent = accent, root = chord.root)
                } else {
                    val shape = CordaChords.bassBox(chord.id, instrument) ?: CordaChords.frets(chord.id, instrument)
                    if (shape != null) {
                        GuitarChordDiagram(
                            frets = shape,
                            accent = accent,
                            stringCount = instrument.courseCount,
                            modifier = Modifier.size(width = 190.dp, height = 200.dp),
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                            modifier = Modifier.size(width = 190.dp, height = 200.dp).testTag("study.chord.noShape"),
                        ) {
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Outlined.HelpOutline, contentDescription = null, tint = CzTokens.textTertiary, modifier = Modifier.size(26.dp))
                            Text(
                                text = stringResource(R.string.cadentia_study_no_shape),
                                fontSize = 12.5.sp,
                                color = CzTokens.textTertiary,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
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
internal fun GuitarChordDiagram(
    frets: List<Int>,
    accent: Color,
    /**
     * O braço desenhado é o DO INSTRUMENTO. Era 6, fixo: escolher Baixo
     * desenhava seis cordas para um instrumento de quatro, e os pontos caíam
     * nas cordas erradas.
     */
    stringCount: Int = 6,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val stringCount = maxOf(2, stringCount)
        val fretCount = 5
        val inset = 22.dp.toPx()
        val gridWidth = size.width - inset * 2
        val gridHeight = size.height - inset * 2
        val stringGap = gridWidth / (stringCount - 1)
        val fretGap = gridHeight / fretCount

        val positive = frets.filter { it > 0 }
        val baseFret = if ((positive.maxOrNull() ?: 0) > 4) (positive.minOrNull() ?: 1) else 1

        // O ébano, a pestana e os trastes: o mesmo material do braço das
        // escalas (port do `ChordDiagram` do iOS). Duas telas irmãs com dois
        // desenhos diferentes é o que faz um app parecer montado por pessoas
        // diferentes.
        val board = Rect(inset, inset, inset + gridWidth, inset + gridHeight)
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF291A0D), Color(0xFF3B2412), Color(0xFF21140A)),
                startY = board.top, endY = board.bottom,
            ),
            topLeft = board.topLeft, size = board.size,
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
        for (s in 0 until stringCount) {
            val x = inset + s * stringGap
            // A corda mais grave é a mais grossa, como no braço.
            val thickness = (1.9f - s.toFloat() / maxOf(stringCount - 1, 1) * 1.0f).dp.toPx()
            drawLine(
                Color.Black.copy(alpha = 0.45f),
                Offset(x + 0.7f.dp.toPx(), inset), Offset(x + 0.7f.dp.toPx(), inset + gridHeight),
                strokeWidth = thickness + 0.7f.dp.toPx(),
            )
            drawLine(
                Color(0xFFDBDBDB),
                Offset(x, inset), Offset(x, inset + gridHeight),
                strokeWidth = thickness,
            )
        }
        for (f in 0..fretCount) {
            val y = inset + f * fretGap
            val isNut = f == 0 && baseFret == 1
            if (isNut) {
                // A pestana: osso.
                drawLine(
                    Color(0xFFEDE6D1),
                    Offset(inset, y), Offset(inset + gridWidth, y),
                    strokeWidth = 3.5.dp.toPx(),
                )
            } else {
                drawLine(
                    Color.Black.copy(alpha = 0.5f),
                    Offset(inset, y), Offset(inset + gridWidth, y),
                    strokeWidth = 2.dp.toPx(),
                )
                drawLine(
                    Color(0xFFC2C2C2).copy(alpha = 0.8f),
                    Offset(inset, y - 0.6f.dp.toPx()), Offset(inset + gridWidth, y - 0.6f.dp.toPx()),
                    strokeWidth = 1.1f.dp.toPx(),
                )
            }
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
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                else -> {
                    val row = fret - baseFret
                    if (row in 0 until fretCount) {
                        val centre = Offset(x, inset + (row + 0.5f) * fretGap)
                        val r = 7.5.dp.toPx()
                        // Sombra, bolinha com gradiente e um fio branco, como o iOS.
                        drawCircle(Color.Black.copy(alpha = 0.55f), r, centre + Offset(0f, 1.5f.dp.toPx()))
                        drawCircle(
                            Brush.verticalGradient(
                                listOf(accent, accent.copy(alpha = 0.72f)),
                                startY = centre.y - r, endY = centre.y + r,
                            ),
                            r, centre,
                        )
                        drawCircle(Color.White.copy(alpha = 0.55f), r, centre, style = Stroke(1.dp.toPx()))
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
    /** `null` = piano. */
    instrument: CordaInstrument? = null,
) {
    val settings by store.settings.collectAsState()
    val scale = ScaleType.find(settings.piano.scaleType)
    // A oitava em que a escala TOCA. Quatro é o meio do piano; num baixo isso
    // é duas oitavas acima de onde o instrumento vive (a mi solta é 41 Hz e a
    // escala saía em 262). Dois é a oitava do braço de um baixo de quatro cordas.
    val octaveBase = if (instrument?.id == "baixo") 2 else 4
    val notes = scale.notesWithFrequency(settings.piano.scaleRoot, octaveBase = octaveBase)

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

    // A escala no instrumento escolhido. O braço já existia; o teclado é o desenho que faltava.
    if (instrument == null) {
        StudyKeyboard(highlighted = scale.notes(settings.piano.scaleRoot), accent = accent, root = settings.piano.scaleRoot)
    } else {
        ScaleFretboard(
            scaleNotes = scale.notes(settings.piano.scaleRoot),
            root = settings.piano.scaleRoot,
            accent = accent,
            instrument = instrument,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }

    // Centrado como no `VStack` do iOS (alinhamento padrão ao centro).
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    ActionPill(
        stringResource(R.string.music_scales_play_scale),
        Icons.Filled.PlayArrow, accent, prominent = true,
    ) {
        val sequence = notes.toMutableList()
        MusicNotes.noteToMidi(settings.piano.scaleRoot, octaveBase)?.let { rootMidi ->
            val octaveUp = rootMidi + 12
            sequence.add(
                ScaleType.ScaleNote(
                    settings.piano.scaleRoot, octaveBase + 1, octaveUp, MusicNotes.midiToFrequency(octaveUp),
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
    /**
     * O braço é o DO INSTRUMENTO, e não um violão sempre: escolher Baixo
     * desenhava seis cordas com os nomes errados por cima, e a escala aparecia
     * em posições que não existem no instrumento escolhido.
     */
    instrument: CordaInstrument? = null,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    // Uma linha por ORDEM, e não por corda: a viola caipira tem dez cordas em
    // cinco ordens. De cima para baixo, da mais aguda para a mais grave, como
    // se lê diagrama de braço; a mais grave de cada ordem dá o nome da corda.
    val openPitchClass = remember(instrument?.id) {
        if (instrument == null) {
            listOf(4, 11, 7, 2, 9, 4)
        } else {
            val byCourse = HashMap<Int, Int>()
            for (spec in instrument.strings) byCourse[spec.course] = minOf(byCourse[spec.course] ?: Int.MAX_VALUE, spec.midi)
            byCourse.keys.sortedDescending().map { ((byCourse.getValue(it) % 12) + 12) % 12 }
        }
    }
    val openStrings = openPitchClass.map { MusicNotes.noteNames[it] }

    // Sem caixa em volta: no iOS o Canvas fica solto no fundo, e é a madeira
    // que delimita o braço.
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val strings = openPitchClass.size
            // O baixo tem escala longa e vinte casas; o violão, dezessete. Doze
            // é a volta da oitava e cabe em qualquer um.
            val frets = 12
            // Port 1:1 do `ScaleFretboard` do iOS 1.16: uma calha à esquerda
            // para o nome da corda e a nota solta, e uma faixa embaixo para o
            // número da casa.
            val gutter = 46.dp.toPx()
            val insetY = 13.dp.toPx()
            val footer = 18.dp.toPx()
            val boardX = gutter
            val boardWidth = size.width - gutter - 10.dp.toPx()
            val boardHeight = size.height - insetY * 2 - footer
            val stringGap = boardHeight / (strings - 1)
            val fretGap = boardWidth / frets
            val board = Rect(boardX, insetY, boardX + boardWidth, insetY + boardHeight)

            // O ébano. O braço era linha branca sobre preto: legível e sem
            // instrumento nenhum ali. Madeira de verdade custa três gradientes
            // e faz o diagrama pertencer ao mesmo app que desenha o braço do
            // Cordas.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF291A0D), Color(0xFF3D2614), Color(0xFF21140A)),
                    startY = board.top, endY = board.bottom,
                ),
                topLeft = board.topLeft, size = board.size,
                cornerRadius = CornerRadius(3.dp.toPx()),
            )

            // Marcadores de casa, ANTES das cordas: ficam embutidos na
            // madeira, e tudo o que vem depois passa por cima deles.
            val middle = board.center.y
            for (marker in listOf(3, 5, 7, 9, 12)) {
                val x = board.left + (marker - 0.5f) * fretGap
                val radius = 4.2.dp.toPx()
                // Na casa doze são dois, um em cima e um embaixo.
                val centres = if (marker == 12) listOf(middle - stringGap * 0.9f, middle + stringGap * 0.9f) else listOf(middle)
                for (y in centres) drawCircle(Color(0xFF9E9E9E).copy(alpha = 0.32f), radius, Offset(x, y))
            }

            // Trastes e pestana.
            for (f in 0..frets) {
                val x = board.left + f * fretGap
                if (f == 0) {
                    // A pestana: osso, e mais grossa que qualquer traste.
                    drawLine(Color(0xFFEDE6D1), Offset(x, board.top), Offset(x, board.bottom), strokeWidth = 3.5.dp.toPx())
                } else {
                    // Um traste é metal com um brilho em cima e uma sombra
                    // embaixo. Uma linha cinza chapada é uma grade.
                    drawLine(Color.Black.copy(alpha = 0.5f), Offset(x, board.top), Offset(x, board.bottom), strokeWidth = 2.2.dp.toPx())
                    val hx = x - 0.6f.dp.toPx()
                    drawLine(Color(0xFFC7C7C7).copy(alpha = 0.85f), Offset(hx, board.top), Offset(hx, board.bottom), strokeWidth = 1.2.dp.toPx())
                }
            }

            // Cordas: a de cima é a mais aguda, fina; a de baixo, a mais
            // grave, grossa; as duas mais graves em bronze.
            for (s in 0 until strings) {
                val y = board.top + s * stringGap
                val thickness = (0.9f + s.toFloat() / maxOf(strings - 1, 1) * 1.9f).dp.toPx()
                drawLine(
                    Color.Black.copy(alpha = 0.45f),
                    Offset(board.left, y + 1.dp.toPx()), Offset(board.right, y + 1.dp.toPx()),
                    strokeWidth = thickness + 0.8f.dp.toPx(),
                )
                drawLine(
                    if (s >= strings - 2) Color(0xFFC7A366) else Color(0xFFDBDBDB),
                    Offset(board.left, y), Offset(board.right, y),
                    strokeWidth = thickness,
                )
                val label = textMeasurer.measure(
                    openStrings[s],
                    TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
                )
                drawText(
                    label,
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(15.dp.toPx() - label.size.width / 2f, y - label.size.height / 2f),
                )
            }

            // As notas da escala, com o NOME dentro. Um ponto diz onde pôr o
            // dedo; o nome diz que nota é, e é a segunda coisa que faz alguém
            // aprender a escala em vez de decorar um desenho.
            for (s in 0 until strings) {
                val y = board.top + s * stringGap
                for (f in 0..frets) {
                    val pitchClass = (openPitchClass[s] + f) % 12
                    val name = MusicNotes.noteNames[pitchClass]
                    if (name !in scaleNotes) continue
                    val x = if (f == 0) gutter - 15.dp.toPx() else board.left + (f - 0.5f) * fretGap
                    val isRoot = name == root
                    val radius = (if (isRoot) 10.dp else 8.6.dp).toPx()
                    val centre = Offset(x, y)
                    drawCircle(Color.Black.copy(alpha = 0.55f), radius, centre + Offset(0f, 1.5f.dp.toPx()))
                    if (isRoot) {
                        drawCircle(
                            Brush.verticalGradient(
                                listOf(accent, accent.copy(alpha = 0.72f)),
                                startY = centre.y - radius, endY = centre.y + radius,
                            ),
                            radius, centre,
                        )
                    } else {
                        drawCircle(Color(0xFF212121).copy(alpha = 0.94f), radius, centre)
                    }
                    drawCircle(
                        if (isRoot) Color.White.copy(alpha = 0.85f) else accent.copy(alpha = 0.55f),
                        radius, centre,
                        style = Stroke((if (isRoot) 1.6f else 1.2f).dp.toPx()),
                    )
                    val label = textMeasurer.measure(
                        name,
                        TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Black),
                    )
                    drawText(
                        label,
                        color = if (isRoot) Color.Black else Color.White.copy(alpha = 0.92f),
                        topLeft = Offset(x - label.size.width / 2f, y - label.size.height / 2f),
                    )
                }
            }

            // O número da casa, na faixa de baixo.
            for (marker in listOf(3, 5, 7, 9, 12)) {
                val label = textMeasurer.measure(
                    "$marker",
                    TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                )
                drawText(
                    label,
                    color = Color.White.copy(alpha = 0.42f),
                    topLeft = Offset(
                        board.left + (marker - 0.5f) * fretGap - label.size.width / 2f,
                        board.bottom + footer * 0.62f - label.size.height / 2f,
                    ),
                )
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
