package com.levelhard.cadentia.kit

/**
 * Encaixa um acorde na janela de teclas que a tela desenha — port do
 * `PianoVoicing.swift` (1.16).
 *
 * A biblioteca escreve cada acorde **na altura em que ele soa**: G maior é
 * `G3, B3, D4`, e trinta e três dos setenta e sete acordes começam na oitava
 * 3. O teclado de estudo desenha duas oitavas a partir de C4, e a tela
 * descartava o que caísse fora — nos trinta e três, quem caía fora era **a
 * tônica**. Deslocar o acorde inteiro por oitavas preserva a FORMA, que é o
 * que um diagrama ensina. Mora no Kit porque é a parte que dá para provar.
 */
object PianoVoicing {
    private data class Piece(val name: String, val octave: Int)

    /** Uma nota escrita, separada em classe de altura e oitava. */
    private fun split(note: String): Piece? {
        val octave = note.lastOrNull()?.digitToIntOrNull() ?: return null
        val name = note.dropLast(1)
        if (MusicNotes.pitchClass(name) == null) return null
        return Piece(name, octave)
    }

    /**
     * A mesma voz, deslocada por oitavas inteiras para caber em `octaves`
     * oitavas a partir de `base`. Notas sem oitava (o formato das escalas)
     * passam intactas. Se nem deslocando couber, salva a nota mais grave:
     * a tônica está embaixo, e perder a tônica é o pior resultado possível.
     */
    fun fitted(notes: List<String>, octaves: Int = 2, base: Int = 4): List<String> {
        val parsed = notes.map { it to split(it) }
        val lowest = parsed.mapNotNull { it.second?.octave }.minOrNull() ?: return notes
        val shift = base - lowest
        if (shift == 0) return notes
        return parsed.map { (original, piece) -> if (piece == null) original else "${piece.name}${piece.octave + shift}" }
    }

    /** `true` quando toda nota da voz cai dentro da janela desenhada. */
    fun fits(notes: List<String>, octaves: Int = 2, base: Int = 4): Boolean = notes.all { note ->
        val piece = split(note) ?: return@all true
        piece.octave >= base && piece.octave < base + octaves
    }
}
