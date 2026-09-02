package com.levelhard.cadentia.kit

// GERADO por scripts/gen-backing-tracks.py a partir do
// BackingTrackCatalog.swift do cadentia-ios. Não edite: os dados são a
// fonte da verdade DO APP (decisão do founder registrada no iOS) e a
// lógica de montagem vive em BackingTrackBuild.kt.

/**
 * Template declarativo de base: progressão de acordes (um por compasso)
 * + um groove. `build()` (em BackingTrackBuild.kt) monta a tablatura.
 */
data class BackingTrack(
    val id: String,
    val nameKey: String,
    val genre: String,
    val key: String,
    val scaleType: String?,
    val bpm: Int,
    val measureCount: Int,
    val timeSignature: List<Int>,
    val chordProgression: List<String>,
    val drumPatternId: String,
) {
    companion object {
        val genres = listOf("rock", "blues", "jazz", "funk", "bossa", "pop", "latin", "electronic")

        fun byGenre(genre: String): List<BackingTrack> = all.filter { it.genre == genre }

        val all: List<BackingTrack> = listOf(
            BackingTrack("rock-am-4bar", "tablature.backingTracks.tracks.rockAm4bar", "rock", "A", "minor-natural", 100, 16, listOf(4, 4), listOf("Am", "F", "C", "G", "Am", "F", "C", "G", "F", "C", "G", "Am", "F", "G", "Am", "Am"), "rock-basic"),
            BackingTrack("rock-g-8bar", "tablature.backingTracks.tracks.rockG8bar", "rock", "G", "major", 120, 16, listOf(4, 4), listOf("G", "C", "D", "G", "G", "C", "D", "D", "Em", "C", "G", "D", "Em", "C", "D", "G"), "rock-driving"),
            BackingTrack("rock-e-power", "tablature.backingTracks.tracks.rockEPower", "rock", "E", "minor-natural", 140, 12, listOf(4, 4), listOf("E", "D", "A", "E", "E", "D", "A", "A", "C", "D", "E", "E"), "metal-double"),
            BackingTrack("rock-em-ballad", "tablature.backingTracks.tracks.rockEmBallad", "rock", "E", "minor-natural", 75, 16, listOf(4, 4), listOf("Em", "Cmaj7", "G", "D", "Em", "Cmaj7", "G", "D", "Am", "Bm", "Cmaj7", "D", "Em", "Cmaj7", "G", "G"), "rock-half-time"),
            BackingTrack("rock-d-stoner", "tablature.backingTracks.tracks.rockDStoner", "rock", "D", "minor-natural", 90, 12, listOf(4, 4), listOf("Dm", "Am", "C", "G", "Dm", "Am", "C", "G", "F", "C", "Dm", "Dm"), "rock-driving"),
            BackingTrack("blues-a-12bar", "tablature.backingTracks.tracks.bluesA12bar", "blues", "A", "blues", 90, 12, listOf(4, 4), listOf("A7", "D7", "A7", "A7", "D7", "D7", "A7", "A7", "E7", "D7", "A7", "E7"), "blues-shuffle"),
            BackingTrack("blues-e-12bar", "tablature.backingTracks.tracks.bluesE12bar", "blues", "E", "blues", 80, 12, listOf(4, 4), listOf("E7", "E7", "E7", "E7", "A7", "A7", "E7", "E7", "B7", "A7", "E7", "B7"), "blues-shuffle"),
            BackingTrack("blues-g-slow", "tablature.backingTracks.tracks.bluesGSlow", "blues", "G", "blues", 65, 12, listOf(4, 4), listOf("G7", "G7", "G7", "G7", "C7", "C7", "G7", "G7", "D7", "C7", "G7", "D7"), "blues-shuffle"),
            BackingTrack("blues-am-minor", "tablature.backingTracks.tracks.bluesAmMinor", "blues", "A", "minor-natural", 85, 12, listOf(4, 4), listOf("Am7", "Am7", "Am7", "Am7", "Dm7", "Dm7", "Am7", "Am7", "Fmaj7", "E7", "Am7", "E7"), "blues-shuffle"),
            BackingTrack("jazz-cmaj7-turnaround", "tablature.backingTracks.tracks.jazzCmaj7Turnaround", "jazz", "C", "major", 110, 8, listOf(4, 4), listOf("Cmaj7", "Am7", "Dm7", "G7", "Em7", "A7", "Dm7", "G7"), "jazz-swing"),
            BackingTrack("jazz-am7-ii-v-i", "tablature.backingTracks.tracks.jazzAm7IIvI", "jazz", "A", "minor-natural", 100, 16, listOf(4, 4), listOf("Dm7", "G7", "Cmaj7", "Cmaj7", "Am7", "D7", "Gmaj7", "Gmaj7", "Em7", "A7", "Dmaj7", "Dmaj7", "Bm7", "E7", "Amaj7", "Amaj7"), "jazz-swing"),
            BackingTrack("jazz-bebop-bridge", "tablature.backingTracks.tracks.jazzBebopBridge", "jazz", "D", "dorian", 160, 32, listOf(4, 4), listOf("Cmaj7", "Am7", "Dm7", "G7", "Cmaj7", "Am7", "Dm7", "G7", "C7", "Fmaj7", "Fm7", "Cmaj7", "Dm7", "G7", "Cmaj7", "Cmaj7", "Cmaj7", "Am7", "Dm7", "G7", "Cmaj7", "Am7", "Dm7", "G7", "C7", "Fmaj7", "Fm7", "Cmaj7", "Dm7", "G7", "Cmaj7", "Cmaj7"), "jazz-bebop"),
            BackingTrack("jazz-modal-em7", "tablature.backingTracks.tracks.jazzModalEm7", "jazz", "E", "dorian", 95, 16, listOf(4, 4), listOf("Em7", "Em7", "Em7", "Em7", "Am7", "Am7", "Am7", "Am7", "Em7", "Em7", "Em7", "Em7", "Am7", "B7", "Em7", "Em7"), "jazz-swing"),
            BackingTrack("funk-em-groove", "tablature.backingTracks.tracks.funkEmGroove", "funk", "E", "minor-natural", 110, 8, listOf(4, 4), listOf("Em7", "Em7", "Em7", "Em7", "Am7", "Am7", "Em7", "Em7"), "funk-basic"),
            BackingTrack("funk-d-dorian", "tablature.backingTracks.tracks.funkDDorian", "funk", "D", "dorian", 105, 8, listOf(4, 4), listOf("Dm7", "G7", "Dm7", "G7", "Dm7", "G7", "Cmaj7", "A7"), "funk-basic"),
            BackingTrack("funk-am-shuffle", "tablature.backingTracks.tracks.funkAmShuffle", "funk", "A", "dorian", 100, 12, listOf(4, 4), listOf("Am7", "D7", "Am7", "Am7", "D7", "D7", "Am7", "Am7", "E7", "D7", "Am7", "E7"), "funk-shuffle"),
            BackingTrack("funk-disco-a", "tablature.backingTracks.tracks.funkDiscoA", "funk", "A", "minor-natural", 120, 16, listOf(4, 4), listOf("Am7", "Dm7", "G7", "Cmaj7", "Am7", "Dm7", "G7", "Cmaj7", "Fmaj7", "Em7", "Dm7", "G7", "Am7", "Dm7", "G7", "Cmaj7"), "disco"),
            BackingTrack("bossa-cmaj7", "tablature.backingTracks.tracks.bossaCmaj7", "bossa", "C", "major", 85, 16, listOf(4, 4), listOf("Cmaj7", "Cmaj7", "Dm7", "G7", "Cmaj7", "Cmaj7", "Dm7", "G7", "Fmaj7", "Fm7", "Em7", "A7", "Dm7", "G7", "Cmaj7", "Cmaj7"), "bossa-nova"),
            BackingTrack("bossa-am-girlipanema", "tablature.backingTracks.tracks.bossaAmGirlIpanema", "bossa", "A", "major", 90, 16, listOf(4, 4), listOf("Amaj7", "Amaj7", "B7", "B7", "Bm7", "E7", "Amaj7", "Amaj7", "Amaj7", "Amaj7", "B7", "B7", "Bm7", "E7", "Amaj7", "Amaj7"), "bossa-nova"),
            BackingTrack("bossa-dm-quietnight", "tablature.backingTracks.tracks.bossaDmQuietNight", "bossa", "D", "minor-natural", 80, 16, listOf(4, 4), listOf("Dm7", "G7", "Cmaj7", "Cmaj7", "Bm7b5", "E7", "Am7", "Am7", "Dm7", "G7", "Cmaj7", "A7", "Dm7", "G7", "Cmaj7", "Cmaj7"), "bossa-nova"),
            BackingTrack("pop-4chord-c", "tablature.backingTracks.tracks.pop4ChordC", "pop", "C", "major", 100, 8, listOf(4, 4), listOf("C", "G", "Am", "F", "C", "G", "F", "F"), "pop-basic"),
            BackingTrack("pop-axis-g", "tablature.backingTracks.tracks.popAxisG", "pop", "G", "major", 95, 8, listOf(4, 4), listOf("G", "D", "Em", "C", "G", "D", "C", "C"), "pop-basic"),
            BackingTrack("pop-50s-c", "tablature.backingTracks.tracks.pop50sC", "pop", "C", "major", 110, 8, listOf(4, 4), listOf("C", "Am", "F", "G", "C", "Am", "F", "G"), "pop-basic"),
            BackingTrack("pop-ballad-em", "tablature.backingTracks.tracks.popBalladEm", "pop", "E", "minor-natural", 70, 8, listOf(4, 4), listOf("Em", "C", "G", "D", "Em", "C", "D", "D"), "ballad-slow"),
            BackingTrack("latin-am-progression", "tablature.backingTracks.tracks.latinAmProgression", "latin", "A", "minor-harmonic", 120, 8, listOf(4, 4), listOf("Am", "G", "F", "E", "Am", "G", "F", "E"), "latin-clave"),
            BackingTrack("latin-em-flamenco", "tablature.backingTracks.tracks.latinEmFlamenco", "latin", "E", "minor-harmonic", 130, 8, listOf(4, 4), listOf("Em", "Am", "B7", "Em", "Am", "G", "F", "E"), "latin-clave"),
            BackingTrack("latin-samba-c", "tablature.backingTracks.tracks.latinSambaC", "latin", "C", "major", 105, 16, listOf(4, 4), listOf("Cmaj7", "A7", "Dm7", "G7", "Cmaj7", "A7", "Dm7", "G7", "Em7", "A7", "Dm7", "G7", "Cmaj7", "A7", "Dm7", "G7"), "samba"),
            BackingTrack("latin-mambo-c", "tablature.backingTracks.tracks.latinMamboC", "latin", "C", "major", 175, 8, listOf(4, 4), listOf("C", "F", "G7", "C", "C", "F", "G7", "C"), "mambo"),
            BackingTrack("latin-reggae-am", "tablature.backingTracks.tracks.latinReggaeAm", "latin", "A", "minor-natural", 75, 8, listOf(4, 4), listOf("Am", "G", "F", "G", "Am", "G", "F", "G"), "reggae-one-drop"),
            BackingTrack("electronic-hiphop-cm", "tablature.backingTracks.tracks.electronicHipHopCm", "electronic", "C", "minor-natural", 90, 8, listOf(4, 4), listOf("Cm", "Fm", "Cm", "G7", "Cm", "Fm", "Dm7b5", "G7"), "hip-hop-basic"),
            BackingTrack("electronic-trap-fm", "tablature.backingTracks.tracks.electronicTrapFm", "electronic", "F", "minor-natural", 140, 8, listOf(4, 4), listOf("Fm", "Cm", "Fm", "Cm", "Fm", "Cm", "Dm7b5", "Cm"), "hip-hop-trap"),
            BackingTrack("electronic-house-am", "tablature.backingTracks.tracks.electronicHouseAm", "electronic", "A", "minor-natural", 124, 8, listOf(4, 4), listOf("Am7", "Fmaj7", "Cmaj7", "G", "Am7", "Fmaj7", "Dm7", "G"), "house"),
            BackingTrack("electronic-techno-em", "tablature.backingTracks.tracks.electronicTechnoEm", "electronic", "E", "minor-natural", 128, 8, listOf(4, 4), listOf("Em", "Em", "C", "G", "Em", "Em", "C", "D"), "techno"),
            BackingTrack("electronic-dnb-am", "tablature.backingTracks.tracks.electronicDnbAm", "electronic", "A", "minor-natural", 174, 8, listOf(4, 4), listOf("Am", "F", "Am", "G", "Am", "F", "C", "G"), "drum-and-bass"),
            BackingTrack("rock-hard-a", "tablature.backingTracks.tracks.rockHardA", "rock", "A", "minor-natural", 132, 16, listOf(4, 4), listOf("Am", "Am", "G", "G", "F", "F", "E7", "E7", "Am", "Am", "G", "G", "F", "E7", "Am", "Am"), "rock-driving"),
            BackingTrack("rock-punk-c", "tablature.backingTracks.tracks.rockPunkC", "rock", "C", "major", 176, 8, listOf(4, 4), listOf("C", "G", "Am", "F", "C", "G", "F", "G"), "rock-basic"),
            BackingTrack("blues-jazz-f", "tablature.backingTracks.tracks.bluesJazzF", "blues", "F", "mixolydian", 120, 12, listOf(4, 4), listOf("F7", "B7", "F7", "Cm7", "B7", "B7", "F7", "Am7b5", "Gm7", "C7", "F7", "C7"), "blues-shuffle"),
            BackingTrack("blues-slow-c", "tablature.backingTracks.tracks.bluesSlowC", "blues", "C", "mixolydian", 62, 12, listOf(4, 4), listOf("C7", "F7", "C7", "C7", "F7", "F7", "C7", "C7", "G7", "F7", "C7", "G7"), "blues-shuffle"),
            BackingTrack("jazz-minor-ii-v", "tablature.backingTracks.tracks.jazzMinorIiV", "jazz", "A", "minor-harmonic", 132, 16, listOf(4, 4), listOf("Bm7b5", "E7", "Am7", "Am7", "Bm7b5", "E7", "Am7", "Am7", "Dm7", "G7", "Cmaj7", "Cmaj7", "Bm7b5", "E7", "Am7", "Am7"), "jazz-swing"),
            BackingTrack("jazz-dorian-dm", "tablature.backingTracks.tracks.jazzDorianDm", "jazz", "D", "dorian", 112, 16, listOf(4, 4), listOf("Dm7", "Dm7", "Dm7", "Dm7", "G7", "G7", "G7", "G7", "Dm7", "Dm7", "Dm7", "Dm7", "Em7b5", "A7", "Dm7", "Dm7"), "jazz-bebop"),
            BackingTrack("funk-slow-cm", "tablature.backingTracks.tracks.funkSlowCm", "funk", "C", "dorian", 92, 16, listOf(4, 4), listOf("Cm7", "Cm7", "Fm7", "Fm7", "Cm7", "Cm7", "G7", "G7", "Cm7", "Cm7", "Fm7", "Fm7", "Dm7b5", "G7", "Cm7", "Cm7"), "funk-shuffle"),
            BackingTrack("funk-disco-em", "tablature.backingTracks.tracks.funkDiscoEm", "funk", "E", "minor-natural", 118, 16, listOf(4, 4), listOf("Em7", "Am7", "Em7", "Am7", "Cmaj7", "Bm7", "Em7", "Em7", "Em7", "Am7", "Em7", "Am7", "Cmaj7", "B7", "Em7", "Em7"), "disco"),
            BackingTrack("bossa-fmaj7", "tablature.backingTracks.tracks.bossaFmaj7", "bossa", "F", "major", 132, 16, listOf(4, 4), listOf("Fmaj7", "Fmaj7", "Gm7", "C7", "Fmaj7", "Fmaj7", "Gm7", "C7", "Bmaj7", "Bm7", "Am7", "D7", "Gm7", "C7", "Fmaj7", "Fmaj7"), "bossa-nova"),
            BackingTrack("bossa-minor-em", "tablature.backingTracks.tracks.bossaMinorEm", "bossa", "E", "minor-natural", 128, 16, listOf(4, 4), listOf("Em7", "Em7", "Am7", "Am7", "Bm7b5", "B7", "Em7", "Em7", "Am7", "D7", "Gmaj7", "Gmaj7", "Bm7b5", "B7", "Em7", "Em7"), "bossa-nova"),
            BackingTrack("pop-sus-d", "tablature.backingTracks.tracks.popSusD", "pop", "D", "major", 104, 8, listOf(4, 4), listOf("D", "Dsus4", "G", "A", "D", "Dsus4", "G", "G"), "pop-basic"),
            BackingTrack("pop-ballad-c", "tablature.backingTracks.tracks.popBalladC", "pop", "C", "major", 72, 8, listOf(4, 4), listOf("C", "Em", "Am", "F", "C", "Em", "F", "G"), "ballad-slow"),
            BackingTrack("latin-salsa-dm", "tablature.backingTracks.tracks.latinSalsaDm", "latin", "D", "minor-natural", 190, 16, listOf(4, 4), listOf("Dm7", "Dm7", "G7", "G7", "Cmaj7", "Cmaj7", "Am7", "A7", "Dm7", "Dm7", "G7", "G7", "Em7b5", "A7", "Dm7", "Dm7"), "mambo"),
            BackingTrack("electronic-deep-fm", "tablature.backingTracks.tracks.electronicDeepFm", "electronic", "F", "minor-natural", 122, 8, listOf(4, 4), listOf("Fm7", "Cm7", "Fm7", "Cm7", "Dm7b5", "Cm7", "Fm7", "Fm7"), "house"),
        )
    }
}
