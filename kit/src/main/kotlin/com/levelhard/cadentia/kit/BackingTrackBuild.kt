package com.levelhard.cadentia.kit

/**
 * A montagem das bases — port da parte de lógica do
 * `BackingTrackCatalog.swift`: a levada por gênero (o que separa uma base de
 * uma cifra), a linha de baixo em graus, e o `build()` que junta harmonia,
 * baixo e bateria numa tablatura tocável.
 */

/** Uma batida do acorde: em que passo (0..15, semicolcheias) e quanto dura. */
internal data class CompHit(val step: Int, val dur: Int)

internal object BackingGroove {
    /** A levada de cada gênero. Antes toda faixa era um acorde parado. */
    fun comp(genre: String): List<CompHit> = when (genre) {
        "rock" ->
            // Colcheias no braço, o motor do rock.
            (0 until 16 step 2).map { CompHit(it, 2) }
        "blues" ->
            // Suingue: a segunda colcheia atrasa (ternário em semicolcheias).
            listOf(0, 3, 4, 7, 8, 11, 12, 15).map { CompHit(it, if (it % 4 == 0) 3 else 1) }
        "jazz" ->
            // Comping de quatro, à Freddie Green, com a antecipação do 4 e meio.
            listOf(CompHit(0, 3), CompHit(4, 3), CompHit(8, 3), CompHit(14, 2))
        "funk" ->
            // Semicolcheias curtas e secas, com buracos: o silêncio é groove.
            listOf(0, 3, 6, 7, 10, 14).map { CompHit(it, 1) }
        "bossa" ->
            // O desenho clássico do violão de bossa, sincopado entre os tempos.
            listOf(CompHit(0, 3), CompHit(3, 3), CompHit(6, 2), CompHit(10, 3), CompHit(14, 2))
        "latin" ->
            // Montuno: célula de semicolcheias que empurra o próximo compasso.
            listOf(0, 3, 6, 10, 13).map { CompHit(it, 2) }
        "electronic" ->
            // Acorde no contratempo, tempo forte para o bumbo.
            listOf(2, 6, 10, 14).map { CompHit(it, 2) }
        else ->
            // Pop: colcheias com furos, respirando no 3.
            listOf(CompHit(0, 2), CompHit(4, 2), CompHit(6, 2), CompHit(8, 2), CompHit(12, 2), CompHit(14, 2))
    }

    /** Linha de baixo em (passo, grau do acorde, duração). Sem baixo não há chão. */
    fun bassLine(genre: String): List<Triple<Int, Int, Int>> = when (genre) {
        "rock", "electronic" -> (0 until 16 step 2).map { Triple(it, 0, 2) }
        "blues" ->
            // Fundamental, quinta, sexta e volta: o baixo de shuffle.
            listOf(Triple(0, 0, 4), Triple(4, 7, 4), Triple(8, 9, 4), Triple(12, 7, 4))
        "jazz" ->
            // Caminhando: fundamental, quinta, oitava, quinta.
            listOf(Triple(0, 0, 4), Triple(4, 7, 4), Triple(8, 12, 4), Triple(12, 7, 4))
        "funk" ->
            listOf(Triple(0, 0, 2), Triple(3, 0, 1), Triple(6, 12, 2), Triple(10, 0, 2), Triple(14, 7, 2))
        "bossa", "latin" ->
            // Tumbão: fundamental no 1, quinta antecipando o 3.
            listOf(Triple(0, 0, 6), Triple(6, 7, 4), Triple(10, 0, 6))
        else -> listOf(Triple(0, 0, 8), Triple(8, 7, 8))
    }

    /** Altura absoluta de uma corda, para achar a mais grave PELO TOM. */
    fun midiOf(string: Tablature.TuningString): Int {
        val semitone = mapOf(
            "C" to 0, "C#" to 1, "D" to 2, "D#" to 3, "E" to 4, "F" to 5, "F#" to 6,
            "G" to 7, "G#" to 8, "A" to 9, "A#" to 10, "B" to 11,
        )[string.name] ?: 0
        return (string.octave + 1) * 12 + semitone
    }

    /** Casa de uma nota do baixo na corda mais grave (Mi), pelo grau pedido. */
    fun bassFret(root: String, degree: Int): Int? {
        val semitones = mapOf(
            "C" to 8, "C#" to 9, "Db" to 9, "D" to 10, "D#" to 11, "Eb" to 11, "E" to 0,
            "F" to 1, "F#" to 2, "Gb" to 2, "G" to 3, "G#" to 4, "Ab" to 4, "A" to 5,
            "A#" to 6, "Bb" to 6, "B" to 7,
        )
        val base = semitones[root] ?: return null
        return base + degree
    }

    /** As duas cordas mais graves da forma: o papel de baixo do violão no tempo forte. */
    fun bassStrings(chord: Chord): List<Pair<Int, Int>> =
        chord.guitarFrets.withIndex()
            .filter { it.value >= 0 }
            .take(2)
            .map { it.index to it.value }

    /** As cordas de cima: o acorde sem o grave, tocado nos contratempos. */
    fun upperStrings(chord: Chord): List<Pair<Int, Int>> {
        val voiced = chord.guitarFrets.withIndex().filter { it.value >= 0 }
        return voiced.takeLast(maxOf(voiced.size - 2, 2)).map { it.index to it.value }
    }
}

/** Monta a base tocável: harmonia com a levada do gênero, baixo e bateria. */
fun BackingTrack.build(title: String): Tablature {
    val tab = Tablature.createEmpty(title = title, bpm = bpm, measureCount = measureCount)
    tab.meta.author = "Cadentia"
    tab.transport.timeSignature = timeSignature

    val hits = BackingGroove.comp(genre)
    val bass = BackingGroove.bassLine(genre)

    tab.addTrack("bass")
    val bassIdx = tab.tracks.size - 1
    // A corda mais grave pelo TOM, não por posição: o preset guarda grave→
    // aguda (E1 é a linha 0) e assumir o contrário pôs o baixo inteiro na
    // corda Sol, 15 semitons acima do escrito — toda base ficou dissonante.
    val bassTuning = tab.tracks[bassIdx].tuning
    val lowString = bassTuning.indices.minByOrNull { BackingGroove.midiOf(bassTuning[it]) } ?: 0

    for ((index, chordName) in chordProgression.take(measureCount).withIndex()) {
        val chord = ChordLibrary.all.firstOrNull { it.id == chordName } ?: continue
        val base = tab.tracks[0].measureStartColumn(index)

        for ((order, hit) in hits.withIndex()) {
            // Baixo e acorde ALTERNADOS, como se toca de verdade: o tempo
            // forte leva a nota grave sozinha; o resto, as cordas de cima.
            val onBeat = hit.step % 4 == 0
            val strings = if (onBeat && order % 2 == 0) {
                BackingGroove.bassStrings(chord)
            } else {
                BackingGroove.upperStrings(chord)
            }
            for ((row, fret) in strings) {
                tab.setFret(trackIdx = 0, absoluteCol = base + hit.step, rowIdx = row, value = fret, dur = hit.dur)
            }
        }
        tab.addChordMark(measureIdx = index, col = 0, chordId = chord.id, displayName = chord.displayName)

        val bassBase = tab.tracks[bassIdx].measureStartColumn(index)
        for ((step, degree, dur) in bass) {
            val fret = BackingGroove.bassFret(chord.root, degree) ?: continue
            if (fret > 15) continue
            tab.setFret(trackIdx = bassIdx, absoluteCol = bassBase + step, rowIdx = lowString, value = fret, dur = dur)
        }
    }

    tab.addTrack("drums")
    DrumPattern.find(drumPatternId)?.let { pattern ->
        val drumsIdx = tab.tracks.size - 1
        for (measureIdx in 0 until measureCount) {
            tab.fillDrumPattern(pattern, drumsIdx, measureIdx)
        }
    }
    return tab
}
