package com.levelhard.cadentia.kit

import java.text.Normalizer

/**
 * Uma linha do seletor de afinações, com os textos já resolvidos — port do
 * `TuningRow` do iOS. Existe porque o catálogo tem 49 afinações e a folha
 * precisa buscar, agrupar e ordenar sobre texto localizado, coisa que o
 * `InstrumentPreset` (dado puro, gerado) não deve saber fazer.
 */
data class TuningRow(
    val preset: InstrumentPreset,
    /** "Violão", "Viola caipira" — já localizado. */
    val family: String,
    /** "Drop C", "Padrão" — já localizado quando descritivo. Vazio no cromático. */
    val tuning: String,
) {
    val id: String get() = preset.id
    val group: InstrumentPreset.Group get() = preset.group
    val artists: String get() = preset.artists

    /** "D A D G B E", com glifos. */
    val notes: String = preset.notesLine

    /** O título da linha: a afinação — ou a família, quando não há afinação. */
    val title: String get() = if (tuning.isEmpty()) family else tuning

    /** A segunda linha: "Violão · D A D G B E". */
    val subtitle: String get() = if (notes.isEmpty()) family else "$family · $notes"

    /** "Violão · Drop C" — o rótulo curto do botão que abre a folha. */
    val compactLabel: String get() = if (tuning.isEmpty()) family else "$family · $tuning"

    /** Nome, instrumento, notas e id — minúsculo, sem acento. */
    val haystack: String

    /**
     * As bandas, separadas do resto de propósito: "drop b" achava o Drop C
     * porque o "b" casava dentro de "Bullet for My Valentine". Nome de banda
     * só entra na busca a partir de três letras.
     */
    val artistsHaystack: String

    init {
        val ascii = preset.strings.joinToString("") { it.name }
        // O nome entra duas vezes: com glifo ("Drop C♯", que é o que se lê) e
        // em ASCII ("Drop C#", que é o que se digita).
        val asciiTuning = tuning.replace("♯", "#").replace("♭", "b")
        haystack = TuningCatalog.normalized(
            listOf(family, tuning, asciiTuning, notes, ascii, preset.id).joinToString(" "),
        )
        artistsHaystack = TuningCatalog.normalized(preset.artists)
    }
}

/** Monta, busca e agrupa as linhas do seletor de afinações — port do `TuningCatalog`. */
object TuningCatalog {
    /** Quantas afinações recentes ficam fixadas no topo. */
    const val recentLimit = 6

    /** A partir de quantas letras um termo também procura em nome de banda. */
    const val artistSearchMinimum = 3

    /** Minúsculo e sem acento — "Cebolão" casa com "cebolao". */
    fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    /**
     * `localize` traduz uma chave i18n. Vem injetado para o Kit não depender
     * dos resources do app — e para o teste poder passar uma identidade.
     */
    fun rows(
        presets: List<InstrumentPreset> = InstrumentPreset.all,
        localize: (String) -> String,
    ): List<TuningRow> = presets.map { preset ->
        TuningRow(
            preset = preset,
            family = localize(preset.familyKey),
            tuning = preset.nameKey?.let(localize) ?: (preset.name ?: ""),
        )
    }

    /**
     * Busca por qualquer termo: nome, instrumento, notas, banda ou id. Vários
     * termos separados por espaço somam (E), não substituem.
     */
    fun filter(rows: List<TuningRow>, query: String): List<TuningRow> {
        val terms = normalized(query).split(" ").filter { it.isNotEmpty() }
        if (terms.isEmpty()) return rows
        return rows.filter { row ->
            terms.all { term ->
                row.haystack.contains(term) ||
                    (term.length >= artistSearchMinimum && row.artistsHaystack.contains(term))
            }
        }
    }

    data class Section(
        val group: InstrumentPreset.Group,
        val title: String,
        val rows: List<TuningRow>,
    ) {
        val id: String get() = group.id
    }

    /** Agrupa mantendo a ordem de `Group.entries`; seção vazia não aparece. */
    fun sections(rows: List<TuningRow>, localize: (String) -> String): List<Section> =
        InstrumentPreset.Group.entries.mapNotNull { group ->
            val inGroup = rows.filter { it.group == group }
            if (inGroup.isEmpty()) null else Section(group, localize(group.nameKey), inGroup)
        }

    /** Coloca `id` na frente da lista de recentes, sem repetir, cortando no limite. */
    fun pushRecent(id: String, into: List<String>): List<String> =
        (listOf(id) + into.filter { it != id }).take(recentLimit)

    /** As linhas dos recentes, na ordem dos ids. Ignora id que não existe mais. */
    fun recentRows(rows: List<TuningRow>, ids: List<String>): List<TuningRow> =
        ids.mapNotNull { id -> rows.firstOrNull { it.id == id } }
}
