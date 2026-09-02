// GERADO por scripts/gen-instruments.py a partir do InstrumentPreset.swift do
// cadentia-ios (que por sua vez é gerado do instruments.js do roqueos-front).
// Não edite na mão: regenere.
package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.log2

/**
 * Uma afinação do catálogo — 49 no total, mesmos ids da PWA para
 * `tuner.lastInstrument` viajar entre os dois.
 *
 * O nome vem por um de dois caminhos: `nameKey` quando é descritivo e se
 * traduz ("Padrão", "Meio-tom abaixo"), `name` quando é nome próprio e não se
 * traduz ("Drop C", "Cebolão em Ré"). Nunca os dois.
 */
data class InstrumentPreset(
    val id: String,
    val group: Group,
    /** Chave i18n do instrumento ("Violão", "Viola caipira"). */
    val familyKey: String,
    /** Chave i18n do nome quando descritivo; null se for nome próprio. */
    val nameKey: String?,
    /** Nome próprio; null no cromático e nos descritivos. */
    val name: String?,
    /** Quem tornou a afinação conhecida. Texto livre, nunca traduzido, "" quando não há. */
    val artists: String,
    /** Da mais grave para a mais aguda; vazio = cromático (qualquer nota). */
    val strings: List<StringNote>,
) {
    data class StringNote(val name: String, val octave: Int) {
        fun frequency(referenceA: Double = 440.0): Double =
            MusicNotes.frequency(name, octave, referenceA) ?: 0.0

        /** "F♯" — glifo musical, não ASCII. */
        val display: String
            get() = name.replace("#", "♯").replace("b", "♭")
    }

    /** Seção do seletor. A ordem dos casos é a ordem em que aparecem. */
    enum class Group(val id: String) {
        chromatic("chromatic"), guitar("guitar"), bass("bass"), extended("extended"), world("world");

        val nameKey: String get() = "music.tuner.groups.$id"
    }

    val stringCount: Int get() = strings.size

    /** "D A D G B E", com glifos. */
    val notesLine: String get() = strings.joinToString(" ") { it.display }

    data class NearestString(val note: StringNote, val frequency: Double)

    /** Corda mais próxima da frequência detectada (distância log), null no cromático. */
    fun nearestString(hz: Double, referenceA: Double = 440.0): NearestString? {
        if (hz <= 0 || strings.isEmpty()) return null
        var closest: NearestString? = null
        var minDist = Double.POSITIVE_INFINITY
        for (s in strings) {
            val f = s.frequency(referenceA)
            if (f <= 0) continue
            val dist = abs(log2(hz / f))
            if (dist < minDist) {
                minDist = dist
                closest = NearestString(s, f)
            }
        }
        return closest
    }

    companion object {
        fun find(id: String?): InstrumentPreset = all.firstOrNull { it.id == id } ?: all[0]

        val all: List<InstrumentPreset> = listOf(
            InstrumentPreset(
                id = "chromatic", group = Group.chromatic,
                familyKey = "music.tuner.instruments.chromatic",
                nameKey = null, name = null,
                artists = "",
                strings = emptyList(),
            ),
            InstrumentPreset(
                id = "guitar-standard", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("E", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-eb", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = "music.tuner.tunings.halfStepDown", name = null,
                artists = "Jimi Hendrix · Stevie Ray Vaughan · Slash",
                strings = listOf(StringNote("Eb", 2), StringNote("Ab", 2), StringNote("Db", 3), StringNote("Gb", 3), StringNote("Bb", 3), StringNote("Eb", 4)),
            ),
            InstrumentPreset(
                id = "guitar-d", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = "music.tuner.tunings.wholeStepDown", name = null,
                artists = "Soundgarden · Alice in Chains",
                strings = listOf(StringNote("D", 2), StringNote("G", 2), StringNote("C", 3), StringNote("F", 3), StringNote("A", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-c-sharp", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = "music.tuner.tunings.stepAndAHalfDown", name = null,
                artists = "Black Sabbath · Deftones",
                strings = listOf(StringNote("C#", 2), StringNote("F#", 2), StringNote("B", 2), StringNote("E", 3), StringNote("G#", 3), StringNote("C#", 4)),
            ),
            InstrumentPreset(
                id = "guitar-c", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = "music.tuner.tunings.twoStepsDown", name = null,
                artists = "Mastodon",
                strings = listOf(StringNote("C", 2), StringNote("F", 2), StringNote("Bb", 2), StringNote("Eb", 3), StringNote("G", 3), StringNote("C", 4)),
            ),
            InstrumentPreset(
                id = "guitar-drop-d", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Drop D",
                artists = "Nirvana · Foo Fighters · Tool",
                strings = listOf(StringNote("D", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-drop-c-sharp", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Drop C♯",
                artists = "Bring Me the Horizon",
                strings = listOf(StringNote("C#", 2), StringNote("G#", 2), StringNote("C#", 3), StringNote("F#", 3), StringNote("A#", 3), StringNote("D#", 4)),
            ),
            InstrumentPreset(
                id = "guitar-drop-c", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Drop C",
                artists = "System of a Down · Bullet for My Valentine",
                strings = listOf(StringNote("C", 2), StringNote("G", 2), StringNote("C", 3), StringNote("F", 3), StringNote("A", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-drop-b", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Drop B",
                artists = "Slipknot · Killswitch Engage",
                strings = listOf(StringNote("B", 1), StringNote("F#", 2), StringNote("B", 2), StringNote("E", 3), StringNote("G#", 3), StringNote("C#", 4)),
            ),
            InstrumentPreset(
                id = "guitar-drop-a-sharp", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Drop A♯",
                artists = "",
                strings = listOf(StringNote("A#", 1), StringNote("F", 2), StringNote("A#", 2), StringNote("D#", 3), StringNote("G", 3), StringNote("C", 4)),
            ),
            InstrumentPreset(
                id = "guitar-drop-a", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Drop A",
                artists = "",
                strings = listOf(StringNote("A", 1), StringNote("E", 2), StringNote("A", 2), StringNote("D", 3), StringNote("F#", 3), StringNote("B", 3)),
            ),
            InstrumentPreset(
                id = "guitar-double-drop-d", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Double Drop D",
                artists = "Neil Young · Led Zeppelin",
                strings = listOf(StringNote("D", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-open-d", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Open D",
                artists = "Elmore James · Joni Mitchell",
                strings = listOf(StringNote("D", 2), StringNote("A", 2), StringNote("D", 3), StringNote("F#", 3), StringNote("A", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-open-e", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Open E",
                artists = "The Allman Brothers · The Rolling Stones",
                strings = listOf(StringNote("E", 2), StringNote("B", 2), StringNote("E", 3), StringNote("G#", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-open-g", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Open G",
                artists = "Keith Richards · The Black Crowes",
                strings = listOf(StringNote("D", 2), StringNote("G", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-open-a", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Open A",
                artists = "Robert Johnson",
                strings = listOf(StringNote("E", 2), StringNote("A", 2), StringNote("E", 3), StringNote("A", 3), StringNote("C#", 4), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-open-c", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Open C",
                artists = "Soundgarden · John Butler",
                strings = listOf(StringNote("C", 2), StringNote("G", 2), StringNote("C", 3), StringNote("G", 3), StringNote("C", 4), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-open-c6", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Open C6",
                artists = "Jimmy Page · Mumford & Sons",
                strings = listOf(StringNote("C", 2), StringNote("A", 2), StringNote("C", 3), StringNote("G", 3), StringNote("C", 4), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-open-dm", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Open Dm",
                artists = "",
                strings = listOf(StringNote("D", 2), StringNote("A", 2), StringNote("D", 3), StringNote("F", 3), StringNote("A", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-open-gm", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Open Gm",
                artists = "",
                strings = listOf(StringNote("D", 2), StringNote("G", 2), StringNote("D", 3), StringNote("G", 3), StringNote("Bb", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-dadgad", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "DADGAD",
                artists = "Jimmy Page · Pierre Bensusan",
                strings = listOf(StringNote("D", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("A", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-rain-song", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "The Rain Song",
                artists = "Led Zeppelin",
                strings = listOf(StringNote("D", 2), StringNote("G", 2), StringNote("C", 3), StringNote("G", 3), StringNote("C", 4), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "guitar-bruce-palmer", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Bruce Palmer Modal",
                artists = "Crosby, Stills & Nash",
                strings = listOf(StringNote("E", 2), StringNote("E", 2), StringNote("E", 3), StringNote("E", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-nashville", group = Group.guitar,
                familyKey = "music.tuner.families.guitar",
                nameKey = null, name = "Nashville",
                artists = "",
                strings = listOf(StringNote("E", 3), StringNote("A", 3), StringNote("D", 4), StringNote("G", 4), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "bass-4", group = Group.bass,
                familyKey = "music.tuner.instruments.bass4",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("E", 1), StringNote("A", 1), StringNote("D", 2), StringNote("G", 2)),
            ),
            InstrumentPreset(
                id = "bass-4-drop-d", group = Group.bass,
                familyKey = "music.tuner.instruments.bass4",
                nameKey = null, name = "Drop D",
                artists = "",
                strings = listOf(StringNote("D", 1), StringNote("A", 1), StringNote("D", 2), StringNote("G", 2)),
            ),
            InstrumentPreset(
                id = "bass-4-eb", group = Group.bass,
                familyKey = "music.tuner.instruments.bass4",
                nameKey = "music.tuner.tunings.halfStepDown", name = null,
                artists = "",
                strings = listOf(StringNote("Eb", 1), StringNote("Ab", 1), StringNote("Db", 2), StringNote("Gb", 2)),
            ),
            InstrumentPreset(
                id = "bass-5", group = Group.bass,
                familyKey = "music.tuner.instruments.bass5",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("B", 0), StringNote("E", 1), StringNote("A", 1), StringNote("D", 2), StringNote("G", 2)),
            ),
            InstrumentPreset(
                id = "bass-5-tenor", group = Group.bass,
                familyKey = "music.tuner.instruments.bass5",
                nameKey = "music.tuner.tunings.tenor", name = null,
                artists = "",
                strings = listOf(StringNote("E", 1), StringNote("A", 1), StringNote("D", 2), StringNote("G", 2), StringNote("C", 3)),
            ),
            InstrumentPreset(
                id = "bass-6", group = Group.bass,
                familyKey = "music.tuner.families.bass6",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("B", 0), StringNote("E", 1), StringNote("A", 1), StringNote("D", 2), StringNote("G", 2), StringNote("C", 3)),
            ),
            InstrumentPreset(
                id = "guitar-7-standard", group = Group.extended,
                familyKey = "music.tuner.families.guitar7",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "Steve Vai · Dream Theater",
                strings = listOf(StringNote("B", 1), StringNote("E", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-7-drop-a", group = Group.extended,
                familyKey = "music.tuner.families.guitar7",
                nameKey = null, name = "Drop A",
                artists = "Korn",
                strings = listOf(StringNote("A", 1), StringNote("E", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-8-standard", group = Group.extended,
                familyKey = "music.tuner.families.guitar8",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "Meshuggah · Animals as Leaders",
                strings = listOf(StringNote("F#", 1), StringNote("B", 1), StringNote("E", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-8-drop-e", group = Group.extended,
                familyKey = "music.tuner.families.guitar8",
                nameKey = null, name = "Drop E",
                artists = "",
                strings = listOf(StringNote("E", 1), StringNote("B", 1), StringNote("E", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "viola-cebolao-re", group = Group.world,
                familyKey = "music.tuner.families.violaCaipira",
                nameKey = null, name = "Cebolão em Ré",
                artists = "",
                strings = listOf(StringNote("A", 2), StringNote("D", 3), StringNote("F#", 3), StringNote("A", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "viola-cebolao-mi", group = Group.world,
                familyKey = "music.tuner.families.violaCaipira",
                nameKey = null, name = "Cebolão em Mi",
                artists = "",
                strings = listOf(StringNote("B", 2), StringNote("E", 3), StringNote("G#", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "viola-rio-abaixo", group = Group.world,
                familyKey = "music.tuner.families.violaCaipira",
                nameKey = null, name = "Rio-abaixo",
                artists = "",
                strings = listOf(StringNote("G", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("D", 4)),
            ),
            InstrumentPreset(
                id = "viola-boiadeira", group = Group.world,
                familyKey = "music.tuner.families.violaCaipira",
                nameKey = null, name = "Boiadeira",
                artists = "",
                strings = listOf(StringNote("A", 2), StringNote("E", 3), StringNote("G#", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-7-brazil-c", group = Group.world,
                familyKey = "music.tuner.families.sevenString",
                nameKey = null, name = "Em Dó",
                artists = "",
                strings = listOf(StringNote("C", 2), StringNote("E", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "guitar-7-brazil-b", group = Group.world,
                familyKey = "music.tuner.families.sevenString",
                nameKey = null, name = "Em Si",
                artists = "",
                strings = listOf(StringNote("B", 1), StringNote("E", 2), StringNote("A", 2), StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "cavaquinho", group = Group.world,
                familyKey = "music.tuner.instruments.cavaquinho",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("D", 4), StringNote("G", 4), StringNote("B", 4), StringNote("D", 5)),
            ),
            InstrumentPreset(
                id = "banjo", group = Group.world,
                familyKey = "music.tuner.families.banjo",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("D", 4), StringNote("G", 4), StringNote("B", 4), StringNote("D", 5)),
            ),
            InstrumentPreset(
                id = "bandolim", group = Group.world,
                familyKey = "music.tuner.families.bandolim",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("G", 3), StringNote("D", 4), StringNote("A", 4), StringNote("E", 5)),
            ),
            InstrumentPreset(
                id = "ukulele", group = Group.world,
                familyKey = "music.tuner.instruments.ukulele",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("G", 4), StringNote("C", 4), StringNote("E", 4), StringNote("A", 4)),
            ),
            InstrumentPreset(
                id = "ukulele-baritone", group = Group.world,
                familyKey = "music.tuner.instruments.ukulele",
                nameKey = "music.tuner.tunings.baritone", name = null,
                artists = "",
                strings = listOf(StringNote("D", 3), StringNote("G", 3), StringNote("B", 3), StringNote("E", 4)),
            ),
            InstrumentPreset(
                id = "violin", group = Group.world,
                familyKey = "music.tuner.instruments.violin",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("G", 3), StringNote("D", 4), StringNote("A", 4), StringNote("E", 5)),
            ),
            InstrumentPreset(
                id = "viola-arco", group = Group.world,
                familyKey = "music.tuner.families.viola",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("C", 3), StringNote("G", 3), StringNote("D", 4), StringNote("A", 4)),
            ),
            InstrumentPreset(
                id = "cello", group = Group.world,
                familyKey = "music.tuner.families.cello",
                nameKey = "music.tuner.tunings.standard", name = null,
                artists = "",
                strings = listOf(StringNote("C", 2), StringNote("G", 2), StringNote("D", 3), StringNote("A", 3)),
            ),
        )
    }
}
