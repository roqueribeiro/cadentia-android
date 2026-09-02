package com.levelhard.cadentia.features.piano

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

/**
 * A aba Piano — port do `PianoView.swift`: três modos sobre o mesmo sampler
 * de notas: teclado tocável (vozes de teclas), biblioteca de acordes (77
 * formas, bloco/arpejo, diagrama de violão) e explorador de escalas (12
 * tônicas × 12 tipos, braço). No Android o teclado nasce VERTICAL de
 * verdade: graves embaixo, pretas à esquerda, dedo amigo do polegar.
 */
@Composable
fun PianoScreen(store: SettingsStore) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsState()
    val accent = CzTokens.gold

    val sampler = remember { PolyphonicSampler() }
    /** midi → voz soando, para soltar a tecla abafar AQUELA nota. */
    val sounding = remember { mutableMapOf<Int, Long>() }
    var prewarmJob by remember { mutableStateOf<Job?>(null) }
    var mode by remember { mutableStateOf(PianoMode.Piano) }

    val piano = settings.piano
    val voice = InstrumentVoice.from(piano.voice) ?: InstrumentVoice.AcousticPiano

    val heldNoteSeconds = 2.6

    fun noteKey(voice: InstrumentVoice, frequency: Double) = "${voice.id}/$frequency/held"

    /**
     * Pré-renderiza as notas do teclado UMA POR VEZ, devolvendo a vez entre
     * elas (a lição do iOS: o laço fechado deixava a interface passando fome
     * na entrada da tela; a primeira tecla não pode esperar render).
     */
    fun prewarm() {
        prewarmJob?.cancel()
        prewarmJob = scope.launch {
            if (!sampler.startIfNeeded()) return@launch
            val rate = sampler.sampleRate
            val root = (piano.octave + 1) * 12
            for (midi in root until root + 25) {
                val frequency = MusicNotes.midiToFrequency(midi)
                sampler.prewarm(noteKey(voice, frequency)) {
                    InstrumentSynth.render(
                        voice, frequency, heldNoteSeconds,
                        velocity = 0.85f, gain = 0.7f, sampleRate = rate,
                    ).interleaved()
                }
                yield()
            }
        }
    }

    fun noteOn(midi: Int) {
        if (!sampler.startIfNeeded()) return
        val rate = sampler.sampleRate
        val frequency = MusicNotes.midiToFrequency(midi)
        // Retrigger: a mesma tecla abafa a própria nota anterior em vez de
        // empilhar uma segunda cópia.
        sounding[midi]?.let { sampler.damp(it, 0.04f) }
        val handle = sampler.play(noteKey(voice, frequency)) {
            InstrumentSynth.render(
                voice, frequency, heldNoteSeconds,
                velocity = 0.85f, gain = 0.7f, sampleRate = rate,
            ).interleaved()
        }
        if (handle != 0L) sounding[midi] = handle
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun noteOff(midi: Int) {
        val handle = sounding.remove(midi) ?: return
        // Com o pedal, a corda segue soando: é o ponto do sustain.
        if (!store.settings.value.piano.sustain) sampler.damp(handle, 0.13f)
    }

    /** Nota avulsa para acordes/escalas: toca a duração pedida e morre só. */
    fun playNote(frequency: Double, duration: Double = 0.9) {
        if (!sampler.startIfNeeded()) return
        val rate = sampler.sampleRate
        sampler.play("${voice.id}/$frequency/$duration") {
            InstrumentSynth.render(
                voice, frequency, duration,
                velocity = 0.85f, gain = 0.7f, sampleRate = rate,
            ).interleaved()
        }
    }

    LaunchedEffect(piano.voice, piano.octave) { prewarm() }
    DisposableEffect(Unit) {
        onDispose {
            prewarmJob?.cancel()
            sounding.clear()
            sampler.stop()
        }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        if (mode == PianoMode.Piano) {
            // Sem scroll: um gesto de rolagem roubaria o glissando multitoque.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModePicker(mode, accent) { mode = it }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    VoicePicker(voice, accent) { picked ->
                        store.update { it.piano.voice = picked.id }
                        sampler.invalidateCache()
                    }
                    // Pedal de sustain, escrito por extenso (não há símbolo
                    // universal de pedal que leia bem neste tamanho).
                    Surface(
                        onClick = {
                            val enabled = !store.settings.value.piano.sustain
                            store.update { it.piano.sustain = enabled }
                            if (!enabled) {
                                sampler.dampAll(0.16f)
                                sounding.clear()
                            }
                        },
                        shape = CircleShape,
                        color = if (piano.sustain) accent else CzTokens.surface,
                        contentColor = if (piano.sustain) CzTokens.stageBottom else CzTokens.textSecondary,
                    ) {
                        Text(
                            text = stringResource(R.string.cadentia_piano_sustain),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Deslocamento de oitava C2–C6.
                    Text(
                        text = "C${piano.octave}", // i18n-verbatim: nota
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = CzTokens.textSecondary,
                    )
                    RoundIcon(Icons.Filled.Remove) {
                        store.update { it.piano.octave = (it.piano.octave - 1).coerceIn(2, 6) }
                    }
                    RoundIcon(Icons.Filled.Add) {
                        store.update { it.piano.octave = (it.piano.octave + 1).coerceIn(2, 6) }
                    }
                }
                VerticalKeyboard(
                    baseOctave = piano.octave,
                    onNoteOn = ::noteOn,
                    onNoteOff = ::noteOff,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    ModePicker(mode, accent) { mode = it }
                    if (mode == PianoMode.Chords) {
                        ChordsPane(store, accent, ::playNote, scope)
                    } else {
                        ScalesPane(store, accent, ::playNote, scope)
                    }
                }
            }
        }
    }
}

private enum class PianoMode(val labelRes: Int) {
    Piano(R.string.music_tabs_piano),
    Chords(R.string.music_tabs_chords),
    Scales(R.string.music_tabs_scales),
}

@Composable
private fun ModePicker(mode: PianoMode, accent: Color, onPick: (PianoMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (candidate in PianoMode.entries) {
            PillChip(
                text = stringResource(candidate.labelRes),
                active = mode == candidate,
                accent = accent,
            ) { onPick(candidate) }
        }
    }
}

@Composable
private fun VoicePicker(current: InstrumentVoice, accent: Color, onPick: (InstrumentVoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = CzTokens.surface,
            border = BorderStroke(1.dp, CzTokens.hairline),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_more_piano),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = stringResource(I18nMap.res(current.nameKey)),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = CzTokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
                Icon(
                    imageVector = Icons.Filled.UnfoldMore,
                    contentDescription = null,
                    tint = CzTokens.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (candidate in InstrumentVoice.forTrackType("keys")) {
                DropdownMenuItem(
                    text = { Text(stringResource(I18nMap.res(candidate.nameKey))) },
                    trailingIcon = {
                        if (candidate == current) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = accent)
                        }
                    },
                    onClick = {
                        onPick(candidate)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CzTokens.surface,
        contentColor = CzTokens.textSecondary,
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

// MARK: teclado vertical

/**
 * A matemática de layout compartilhada entre o desenho e o hit-testing —
 * os dois TÊM que concordar ou o dedo cai na nota errada (a lição do
 * `KeyboardLayout` do iOS). Vertical: brancas empilhadas, graves EMBAIXO,
 * pretas encostadas na ESQUERDA (62% da largura, cavalgando a divisa).
 */
internal class VerticalKeyboardLayout(val baseOctave: Int, val size: Size) {
    companion object {
        const val WHITE_COUNT = 10
        fun isBlack(midi: Int): Boolean = (midi % 12) in setOf(1, 3, 6, 8, 10)
    }

    val rootMidi: Int get() = (baseOctave + 1) * 12
    val whiteHeight: Float get() = size.height / WHITE_COUNT
    val blackWidth: Float get() = size.width * 0.62f
    val blackHeight: Float get() = whiteHeight * 0.62f

    val whiteMidis: List<Int> by lazy {
        val result = mutableListOf<Int>()
        var midi = rootMidi
        while (result.size < WHITE_COUNT) {
            if (!isBlack(midi)) result.add(midi)
            midi += 1
        }
        result
    }

    /** Retângulo da branca `index` (0 = a mais grave, embaixo). */
    fun whiteRect(index: Int): Rect {
        val top = size.height - (index + 1) * whiteHeight
        return Rect(0f, top, size.width, top + whiteHeight)
    }

    /** Preta cavalgando a divisa acima da branca `index`, se a escala tiver. */
    fun blackMidi(afterWhite: Int): Int? {
        if (afterWhite >= whiteMidis.size) return null
        val candidate = whiteMidis[afterWhite] + 1
        if (candidate >= rootMidi + 17) return null
        return if (isBlack(candidate)) candidate else null
    }

    fun blackRect(afterWhite: Int): Rect {
        val boundary = size.height - (afterWhite + 1) * whiteHeight
        return Rect(0f, boundary - blackHeight / 2, blackWidth, boundary + blackHeight / 2)
    }

    /** Ponto → midi. Pretas ganham dentro da zona delas. */
    fun midiAt(point: Offset): Int? {
        if (point.x < 0 || point.x > size.width || point.y < 0 || point.y > size.height) return null
        if (point.x <= blackWidth) {
            for (index in 0 until WHITE_COUNT) {
                if (blackMidi(afterWhite = index) != null && blackRect(afterWhite = index).contains(point)) {
                    return blackMidi(afterWhite = index)
                }
            }
        }
        val row = ((size.height - point.y) / whiteHeight).toInt().coerceIn(0, WHITE_COUNT - 1)
        return whiteMidis[row]
    }
}

@Composable
private fun VerticalKeyboard(
    baseOctave: Int,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(setOf<Int>()) }
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(baseOctave) {
                // Multitoque de verdade: cada dedo rastreado até a nota; o
                // dedo que desliza para outra tecla redispara (glissando).
                val notes = mutableMapOf<Long, Int>()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val layout = VerticalKeyboardLayout(
                            baseOctave,
                            Size(size.width.toFloat(), size.height.toFloat()),
                        )
                        for (change in event.changes) {
                            val id = change.id.value
                            if (change.pressed) {
                                val midi = layout.midiAt(change.position)
                                if (midi != null && notes[id] != midi) {
                                    notes[id]?.let { previous ->
                                        onNoteOff(previous)
                                        pressed = pressed - previous
                                    }
                                    notes[id] = midi
                                    onNoteOn(midi)
                                    pressed = pressed + midi
                                }
                            } else {
                                notes.remove(id)?.let { midi ->
                                    onNoteOff(midi)
                                    pressed = pressed - midi
                                }
                            }
                            if (change.positionChanged()) change.consume()
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val layout = VerticalKeyboardLayout(baseOctave, size)

            // Brancas.
            for ((index, midi) in layout.whiteMidis.withIndex()) {
                val rect = layout.whiteRect(index)
                val isPressed = midi in pressed
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        if (isPressed) {
                            listOf(Color(0xFFADADAD), Color(0xFFC7C7C7))
                        } else {
                            listOf(Color(0xFFFAFAFA), Color(0xFFE6E6E6))
                        },
                        startX = rect.left,
                        endX = rect.right,
                    ),
                    topLeft = Offset(rect.left, rect.top + 0.5f),
                    size = Size(rect.width, rect.height - 1f),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    color = Color(0xFF8C8C8C).copy(alpha = 0.9f),
                    topLeft = Offset(rect.left, rect.top + 0.5f),
                    size = Size(rect.width, rect.height - 1f),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.7.dp.toPx()),
                )
                if (midi % 12 == 0) {
                    val label = textMeasurer.measure(
                        "C${midi / 12 - 1}",
                        TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    )
                    drawText(
                        label,
                        color = Color(0xFF737373),
                        topLeft = Offset(
                            rect.right - label.size.width - 8.dp.toPx(),
                            rect.center.y - label.size.height / 2f,
                        ),
                    )
                }
            }

            // Pretas por cima, encostadas na esquerda.
            for (index in 0 until VerticalKeyboardLayout.WHITE_COUNT) {
                val midi = layout.blackMidi(afterWhite = index) ?: continue
                val rect = layout.blackRect(afterWhite = index)
                val isPressed = midi in pressed
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        if (isPressed) {
                            listOf(Color(0xFF4D4D4D), Color(0xFF292929))
                        } else {
                            listOf(Color(0xFF292929), Color(0xFF0A0A0A))
                        },
                        startX = rect.left,
                        endX = rect.right,
                    ),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )
                // Face brilhante na ponta livre (direita), como as do GarageBand.
                drawRoundRect(
                    color = Color.White.copy(alpha = if (isPressed) 0.06f else 0.14f),
                    topLeft = Offset(rect.right - 8.dp.toPx(), rect.top + 2.dp.toPx()),
                    size = Size(6.dp.toPx(), rect.height - 4.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
    }
}

// MARK: acordes

@Composable
private fun ChordsPane(
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

private fun playChord(
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
private fun GuitarChordDiagram(frets: List<Int>, accent: Color, modifier: Modifier = Modifier) {
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
private fun ScalesPane(
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
        modifier = Modifier.fillMaxWidth().height(170.dp),
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
private fun ScaleFretboard(
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
            val gridWidth = size.width - insetX - 10.dp.toPx()
            val gridHeight = size.height - insetY * 2
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
                        size.height - 4.dp.toPx() - label.size.height,
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
private fun PillChip(text: String, active: Boolean, accent: Color, onClick: () -> Unit) {
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
private fun ActionPill(
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
