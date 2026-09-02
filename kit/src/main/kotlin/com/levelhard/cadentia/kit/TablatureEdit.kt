package com.levelhard.cadentia.kit

import java.util.UUID

/**
 * API de edição — port do `TablatureEdit.swift` (as funções de mutação do
 * modelo do web: setFret/clearFret/setDuration/addMeasure/addTrack…).
 * Compassos andam em passo travado entre as trilhas, como no editor do web.
 */

/** As DRUM_ROWS do web (subconjunto de 8 pads para tablatura). */
val TABLATURE_DRUM_ROWS: List<Pair<String, String>> = listOf(
    "kick" to "K", "snare" to "S", "hihat-c" to "H", "hihat-o" to "O",
    "clap" to "C", "crash" to "Cr", "ride" to "R", "rim" to "Rm",
)

fun Tablature.Companion.makeTrack(
    type: String,
    measureCount: Int = 4,
    stepsPerMeasure: Int = DEFAULT_STEPS_PER_MEASURE,
): Tablature.Track {
    val track = Tablature.Track()
    track.id = UUID.randomUUID().toString()
    track.type = type
    when (type) {
        "bass" -> {
            track.name = "Bass"
            track.instrumentId = "bass-4"
            track.voiceId = "bass-fingered"
        }
        "drums" -> {
            track.name = "Drums"
            track.kitId = "acoustic"
        }
        "keys" -> {
            track.name = "Keys"
            track.voiceId = "acoustic-piano"
        }
        else -> {
            track.name = "Guitar"
            track.instrumentId = "guitar-standard"
            track.voiceId = "guitar-clean"
        }
    }

    if (type == "guitar" || type == "bass") {
        val preset = InstrumentPreset.find(track.instrumentId)
        track.tuning = preset.strings.map { Tablature.TuningString(it.name, it.octave) }.toMutableList()
        track.rowsMeta = track.tuning.mapIndexed { index, string ->
            Tablature.RowMeta(label = string.name, stringIndex = index)
        }.toMutableList()
    } else if (type == "drums") {
        track.rowsMeta = TABLATURE_DRUM_ROWS.mapIndexed { index, (padId, label) ->
            Tablature.RowMeta(label = label, padId = padId, stringIndex = index)
        }.toMutableList()
    } else {
        // Keys SATB: RH 1-4 + LH 1-4 (valor da célula = MIDI absoluto).
        val voices = listOf(
            Triple("RH 1", 84, "right"), Triple("RH 2", 77, "right"),
            Triple("RH 3", 72, "right"), Triple("RH 4", 67, "right"),
            Triple("LH 1", 60, "left"), Triple("LH 2", 53, "left"),
            Triple("LH 3", 48, "left"), Triple("LH 4", 36, "left"),
        )
        track.rowsMeta = voices.mapIndexed { index, (label, baseMidi, hand) ->
            Tablature.RowMeta(label = label, stringIndex = index, baseMidi = baseMidi, hand = hand)
        }.toMutableList()
    }

    val rowCount = track.rowsMeta.size
    track.measures = MutableList(measureCount) { emptyMeasure(rowCount, stepsPerMeasure) }
    return track
}

private fun emptyMeasure(rowCount: Int, stepsPerMeasure: Int): Tablature.Measure {
    val measure = Tablature.Measure()
    measure.stepsPerMeasure = stepsPerMeasure
    measure.strings = MutableList(rowCount) { index ->
        Tablature.StringLine(
            stringIndex = index,
            steps = MutableList(stepsPerMeasure) { null },
        )
    }
    return measure
}

fun Tablature.Companion.createEmpty(
    title: String = "Untitled",
    bpm: Int = DEFAULT_BPM,
    measureCount: Int = 4,
): Tablature {
    val tab = Tablature()
    tab.meta.title = title
    tab.transport.bpm = bpm
    tab.tracks = mutableListOf(makeTrack("guitar", measureCount))
    return tab
}

/** Coluna absoluta → (measureIdx, stepIdx) dentro de uma trilha. */
fun Tablature.locate(trackIdx: Int, absoluteCol: Int): Pair<Int, Int>? {
    val track = tracks.getOrNull(trackIdx) ?: return null
    var remaining = absoluteCol
    for ((index, measure) in track.measures.withIndex()) {
        if (remaining < measure.stepsPerMeasure) return index to remaining
        remaining -= measure.stepsPerMeasure
    }
    return null
}

fun Tablature.setFret(
    trackIdx: Int,
    absoluteCol: Int,
    rowIdx: Int,
    value: Int,
    dur: Int? = null,
    tup: Int? = null,
) {
    val (measureIdx, stepIdx) = locate(trackIdx, absoluteCol) ?: return
    val strings = tracks[trackIdx].measures[measureIdx].strings
    if (rowIdx !in strings.indices) return
    val cell = Tablature.Cell(v = value)
    // Preserva a figura rítmica existente quando só muda o valor.
    strings[rowIdx].steps[stepIdx]?.let { existing ->
        cell.dur = existing.dur
        cell.tup = existing.tup
        cell.articulations = existing.articulations
    }
    dur?.let { cell.dur = it }
    tup?.let { cell.tup = if (it == 3) 3 else null }
    strings[rowIdx].steps[stepIdx] = cell
}

fun Tablature.clearFret(trackIdx: Int, absoluteCol: Int, rowIdx: Int) {
    val (measureIdx, stepIdx) = locate(trackIdx, absoluteCol) ?: return
    val strings = tracks[trackIdx].measures[measureIdx].strings
    if (rowIdx !in strings.indices) return
    strings[rowIdx].steps[stepIdx] = null
}

/** No-op em célula vazia (semântica do web). */
fun Tablature.setDuration(trackIdx: Int, absoluteCol: Int, rowIdx: Int, dur: Int, tup: Int? = null) {
    val (measureIdx, stepIdx) = locate(trackIdx, absoluteCol) ?: return
    val strings = tracks[trackIdx].measures[measureIdx].strings
    if (rowIdx !in strings.indices) return
    val cell = strings[rowIdx].steps[stepIdx] ?: return
    cell.dur = dur.coerceIn(1, 16)
    cell.tup = if (tup == 3) 3 else null
    strings[rowIdx].steps[stepIdx] = cell
}

/** Repetição por compasso, aplicada em TODAS as trilhas (compasso é conceito da música). */
fun Tablature.setMeasureRepeats(measureIdx: Int, repeats: Int) {
    val clamped = if (repeats == -1) -1 else repeats.coerceIn(1, 16)
    for (track in tracks) {
        if (measureIdx in track.measures.indices) track.measures[measureIdx].repeats = clamped
    }
}

/** Acrescenta um compasso em toda trilha (passo travado). */
fun Tablature.addMeasure(stepsPerMeasure: Int = Tablature.DEFAULT_STEPS_PER_MEASURE) {
    for (track in tracks) {
        track.measures.add(emptyMeasure(track.rowCount, stepsPerMeasure))
    }
}

/** Remove um compasso de toda trilha; recusa remover o último. */
fun Tablature.removeMeasure(at: Int) {
    if ((tracks.firstOrNull()?.measures?.size ?: 0) <= 1) return
    for (track in tracks) {
        if (at in track.measures.indices) track.measures.removeAt(at)
    }
    // Derruba blocos tocando o compasso removido; desloca o resto.
    repeatBlocks = repeatBlocks.mapNotNull { block ->
        if (at in block.startIdx..block.endIdx) return@mapNotNull null
        if (block.startIdx > at) block.startIdx -= 1
        if (block.endIdx > at) block.endIdx -= 1
        block
    }.toMutableList()
    chordMarks = chordMarks.mapNotNull { mark ->
        if (mark.measureIdx == at) return@mapNotNull null
        if (mark.measureIdx > at) mark.measureIdx -= 1
        mark
    }.toMutableList()
}

fun Tablature.addTrack(type: String) {
    val measureCount = tracks.firstOrNull()?.measures?.size ?: 4
    val steps = tracks.firstOrNull()?.measures?.firstOrNull()?.stepsPerMeasure
        ?: Tablature.DEFAULT_STEPS_PER_MEASURE
    tracks.add(Tablature.makeTrack(type, measureCount, steps))
}

/** Recusa remover a última trilha. */
fun Tablature.removeTrack(at: Int) {
    if (tracks.size <= 1 || at !in tracks.indices) return
    tracks.removeAt(at)
}

/** Aplica a forma de violão de um acorde numa coluna (trilhas com traste). */
fun Tablature.insertChord(
    chord: Chord,
    trackIdx: Int,
    measureIdx: Int,
    startCol: Int = 0,
    dur: Int? = null,
) {
    val track = tracks.getOrNull(trackIdx) ?: return
    if (measureIdx !in track.measures.indices) return
    if (track.type != "guitar" && track.type != "bass") return
    val base = track.measureStartColumn(measureIdx)
    val steps = track.measures[measureIdx].stepsPerMeasure
    val column = base + startCol.coerceIn(0, steps - 1)
    for ((stringIdx, fret) in chord.guitarFrets.withIndex()) {
        if (stringIdx < track.rowCount && fret >= 0) {
            setFret(trackIdx, column, stringIdx, fret, dur)
        }
    }
}

/** Preenche um compasso de drums com um groove (truncado; pad desconhecido ignora). */
fun Tablature.fillDrumPattern(pattern: DrumPattern, trackIdx: Int, measureIdx: Int) {
    val track = tracks.getOrNull(trackIdx) ?: return
    if (track.type != "drums" || measureIdx !in track.measures.indices) return
    val steps = track.measures[measureIdx].stepsPerMeasure
    for ((padId, hits) in pattern.pads) {
        val rowIdx = track.rowsMeta.indexOfFirst { it.padId == padId }
        if (rowIdx < 0) continue
        for (step in 0 until minOf(steps, hits.size)) {
            track.measures[measureIdx].strings[rowIdx].steps[step] =
                if (hits[step]) Tablature.Cell(v = 1) else null
        }
    }
}

/** Alterna uma flag de articulação numa célula existente (no-op vazia). */
fun Tablature.toggleArticulation(trackIdx: Int, absoluteCol: Int, rowIdx: Int, key: String) {
    val (measureIdx, stepIdx) = locate(trackIdx, absoluteCol) ?: return
    val strings = tracks[trackIdx].measures[measureIdx].strings
    if (rowIdx !in strings.indices) return
    val cell = strings[rowIdx].steps[stepIdx] ?: return
    val current = cell.articulations[key] ?: false
    cell.articulations = cell.articulations + (key to !current)
    strings[rowIdx].steps[stepIdx] = cell
}

fun Tablature.setTrackVoice(trackIdx: Int, voiceId: String) {
    tracks.getOrNull(trackIdx)?.voiceId = voiceId
}

fun Tablature.setTrackKit(trackIdx: Int, kitId: String) {
    val track = tracks.getOrNull(trackIdx) ?: return
    if (track.type == "drums") track.kitId = kitId
}

/**
 * Adiciona um bloco de repetição em [startIdx..endIdx] (count -1 = infinito).
 * Blocos sobrepostos são substituídos: um bloco por região, como no web.
 */
fun Tablature.addRepeatBlock(startIdx: Int, endIdx: Int, count: Int) {
    val measureCount = tracks.firstOrNull()?.measures?.size ?: return
    if (startIdx < 0 || endIdx < startIdx || endIdx >= measureCount) return
    repeatBlocks.removeAll { it.startIdx <= endIdx && it.endIdx >= startIdx }
    repeatBlocks.add(
        Tablature.RepeatBlock(
            id = UUID.randomUUID().toString(),
            startIdx = startIdx,
            endIdx = endIdx,
            count = if (count == -1) -1 else count.coerceIn(2, 16),
        ),
    )
    repeatBlocks.sortBy { it.startIdx }
}

fun Tablature.removeRepeatBlock(id: String) {
    repeatBlocks.removeAll { it.id == id }
}

fun Tablature.addChordMark(measureIdx: Int, col: Int, chordId: String, displayName: String) {
    chordMarks.add(
        Tablature.ChordMark(
            id = UUID.randomUUID().toString(),
            measureIdx = measureIdx,
            col = col,
            chordId = chordId,
            displayName = displayName,
        ),
    )
}

/**
 * Ordem das linhas na TELA — port do `TabRowDisplay.swift`. O modelo guarda
 * grave→aguda; tablatura impressa mostra a aguda EM CIMA. Guitar/bass
 * invertem; drums não (a ordem é a do kit). A função é o próprio inverso.
 */
object TabRowDisplay {
    fun isReversed(trackType: String): Boolean = trackType == "guitar" || trackType == "bass"

    fun displayRow(row: Int, rowCount: Int, trackType: String): Int =
        if (isReversed(trackType)) maxOf(rowCount - 1 - row, 0) else row
}
