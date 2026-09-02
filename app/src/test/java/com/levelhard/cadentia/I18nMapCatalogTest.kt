package com.levelhard.cadentia

import com.levelhard.cadentia.kit.BackingTrack
import com.levelhard.cadentia.kit.DrumPattern
import com.levelhard.cadentia.kit.DrumSynth
import com.levelhard.cadentia.kit.InstrumentPreset
import com.levelhard.cadentia.kit.InstrumentVoice
import com.levelhard.cadentia.kit.SampleFamily
import com.levelhard.cadentia.kit.MetronomeClick
import com.levelhard.cadentia.kit.ScaleType
import com.levelhard.cadentia.kit.ToneSynth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Toda chave de web que o `:kit` PRODUZ em tempo de execução (nameKey dos
 * catálogos) precisa existir no I18nMap — a auditoria estática só enxerga
 * `R.string.*` literal, e foi exatamente por aí que o Piano em modo Escalas
 * caiu no emulador ("chave i18n desconhecida: music.scales.types.minor-natural").
 * Este teste roda no gate e fecha essa classe de bug para todos os catálogos.
 */
class I18nMapCatalogTest {
    private fun assertResolves(keys: List<String>) {
        val missing = keys.filter { runCatching { I18nMap.res(it) }.isFailure }
        assertEquals("chaves sem R.string: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `scale types resolve (kebab id vira camel como no iOS)`() {
        assertEquals("music.scales.types.minorNatural", ScaleType.all.first { it.id == "minor-natural" }.nameKey)
        assertEquals("music.scales.types.pentatonicMajor", ScaleType.all.first { it.id == "pentatonic-major" }.nameKey)
        assertResolves(ScaleType.all.map { it.nameKey })
    }

    @Test
    fun `instrument voices resolve`() = assertResolves(InstrumentVoice.entries.map { it.nameKey })

    @Test
    fun `sample families resolve`() = assertResolves(SampleFamily.entries.map { it.nameKey })

    @Test
    fun `instrument presets resolve`() {
        assertResolves(InstrumentPreset.all.map { it.familyKey })
        assertResolves(InstrumentPreset.all.mapNotNull { it.nameKey })
        assertResolves(InstrumentPreset.Group.entries.map { it.nameKey })
    }

    @Test
    fun `metronome sounds and tone waves resolve`() {
        assertResolves(MetronomeClick.Sound.entries.map { it.nameKey })
        assertResolves(ToneSynth.Waveform.entries.map { it.nameKey })
    }

    @Test
    fun `drum kits, pads and pattern categories resolve`() {
        assertResolves(DrumSynth.kitIDs.map { DrumSynth.kitNameKey(it) })
        assertResolves(DrumSynth.padIDs.map { DrumSynth.labelKey(it) })
        assertResolves(DrumPattern.all.map { it.nameKey })
        assertResolves(DrumPattern.categories.map { "music.drums.categories.$it" })
    }

    @Test
    fun `backing tracks and genres resolve`() {
        assertResolves(BackingTrack.all.map { it.nameKey })
        assertResolves(BackingTrack.genres.map { "tablature.backingTracks.genres.$it" })
    }
}
