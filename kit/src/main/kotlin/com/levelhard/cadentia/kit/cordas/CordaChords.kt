package com.levelhard.cadentia.kit.cordas

import com.levelhard.cadentia.kit.ChordLibrary
import com.levelhard.cadentia.kit.MusicNotes

/**
 * Formas de acorde para os instrumentos do Cordas — port do `CordaChords.swift`.
 *
 * As formas de seis cordas NÃO são redefinidas aqui: `ChordLibrary` já guarda
 * 77 delas, geradas do `roqueos-front/chords.js`, e todo nome que o app do
 * cordas usa está lá — um segundo catálogo é exatamente a divergência
 * silenciosa que as regras do ecossistema proíbem. A viola caipira É definida
 * aqui, porque cebolão em Ré não existe em lugar nenhum da família ainda e a
 * forma dela é outra forma: cinco valores, um por ORDEM.
 */
object CordaChords {
    /** A ordem dos pads para seis cordas. Os primeiros são os que um iniciante pega. */
    val sixStringSet = listOf(
        "G", "Em", "C", "D", "Am", "F", "A7", "D7", "E", "Dm",
        "B7", "Bm", "Cmaj7", "G7", "E7",
    )

    val violaSet = listOf("D", "G", "A", "A7", "Bm", "Em", "D7", "F#m", "C", "G7")

    /** Viola caipira, cebolão em Ré. Uma casa por ordem, ordem grave primeiro. */
    val violaShapes: Map<String, List<Int>> = mapOf(
        "D" to listOf(0, 0, 0, 0, 0), "G" to listOf(5, 5, 4, 5, 5), "A" to listOf(7, 7, 6, 7, 7),
        "A7" to listOf(7, 7, 6, 7, 5), "Bm" to listOf(2, 4, 4, 2, 2), "Em" to listOf(7, 5, 5, 7, 7),
        "D7" to listOf(0, 0, 0, 0, 3), "F#m" to listOf(4, 4, 2, 4, 4), "C" to listOf(3, 3, 2, 3, 3),
        "G7" to listOf(5, 5, 4, 5, 3),
    )

    /** Os nomes de acorde oferecidos a um instrumento, na ordem dos pads. */
    fun set(instrument: CordaInstrument): List<String> =
        if (instrument.id == "viola") violaSet else sixStringSet

    /**
     * O baixo não toca forma, toca fundamental: a nota certa, na corda mais
     * grave que a alcança nas cinco primeiras casas, com as outras abafadas.
     * Dó maior e dó menor mandam a mesma nota para o baixo.
     */
    internal fun bassRootFrets(chordId: String, instrument: CordaInstrument): List<Int>? {
        val chord = ChordLibrary.all.firstOrNull { it.id == chordId } ?: return null
        val root = MusicNotes.pitchClass(chord.root) ?: return null

        val frets = MutableList(instrument.stringCount) { -1 }
        for ((index, spec) in instrument.strings.withIndex()) {
            val open = spec.midi % 12
            val distance = ((root - open) % 12 + 12) % 12
            if (distance <= 5) {
                frets[index] = distance
                return frets
            }
        }
        // Nenhuma corda alcança em cinco casas: usa a mais grave e sobe.
        val first = instrument.strings.firstOrNull() ?: return null
        frets[0] = ((root - first.midi % 12) % 12 + 12) % 12
        return frets
    }

    /**
     * A CAIXA de arpejo que um baixista toca sobre um acorde — para ESTUDO: onde
     * ficam fundamental, terça, quinta e oitava sem sair do lugar. Derivada da
     * afinação por quartas, não de tabela: fundamental na corda `i` casa `n`,
     * quinta em `i+1` casa `n+2`, oitava em `i+2` casa `n+2`, terça em `i+3` —
     * casa `n+1` se maior, `n` se menor.
     */
    fun bassBox(chordId: String, instrument: CordaInstrument): List<Int>? {
        if (instrument.id != "baixo") return null
        val chord = ChordLibrary.all.firstOrNull { it.id == chordId } ?: return null
        val root = MusicNotes.pitchClass(chord.root) ?: return null

        // A terça vem das NOTAS do acorde, não do nome: "Cmaj7", "C6" e "C" têm
        // terça maior e nomes diferentes.
        val intervals = chord.notes.mapNotNull { MusicNotes.pitchClass(it) }
            .map { (((it - root) % 12) + 12) % 12 }.toSet()
        val minorThird = 3 in intervals && 4 !in intervals

        val frets = MutableList(instrument.stringCount) { -1 }
        for ((index, spec) in instrument.strings.withIndex()) {
            val distance = ((root - spec.midi % 12) % 12 + 12) % 12
            if (distance > 7) continue
            frets[index] = distance
            if (index + 1 < frets.size) frets[index + 1] = distance + 2
            if (index + 2 < frets.size) frets[index + 2] = distance + 2
            if (index + 3 < frets.size) frets[index + 3] = distance + (if (minorThird) 0 else 1)
            return frets
        }
        return bassRootFrets(chordId, instrument)
    }

    /**
     * Casas por CORDA para um acorde neste instrumento. `-1` é corda abafada.
     * `null` quando o instrumento não tem forma para aquele nome — quem chama
     * decide o que isso significa, em vez de ganhar um acorde solto em silêncio.
     *
     * O capotraste faz coisas diferentes em forma e em nota: numa forma ele é
     * PESTANA (`max`), e no pad do baixo ele soma à nota (medido com capo 3:
     * 12 dos 14 primeiros acordes não subiam três semitons com `max`).
     */
    fun frets(chordId: String, instrument: CordaInstrument, capo: Int = 0): List<Int>? {
        val raw: List<Int> = when (instrument.id) {
            "baixo" -> bassRootFrets(chordId, instrument) ?: return null
            "viola" -> {
                val byCourse = violaShapes[chordId] ?: return null
                instrument.strings.map { spec -> if (spec.course < byCourse.size) byCourse[spec.course] else 0 }
            }
            else -> {
                val chord = ChordLibrary.all.firstOrNull { it.id == chordId } ?: return null
                if (chord.guitarFrets.size != instrument.stringCount) return null
                chord.guitarFrets
            }
        }
        return if (instrument.id == "baixo") {
            raw.map { if (it < 0) -1 else it + capo }
        } else {
            raw.map { if (it < 0) -1 else maxOf(it, capo) }
        }
    }
}
