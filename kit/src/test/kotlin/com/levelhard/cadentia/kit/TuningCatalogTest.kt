package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.log2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O catálogo gerado + a busca da folha de afinações — port 1:1 do
 * `TuningCatalogTests.swift`. A tradução entra injetada (`localize`): o eco
 * devolve o último trecho da chave, que é o que o humano leria.
 */
class TuningCatalogTest {
    private fun echo(key: String): String = key.substringAfterLast('.')
    private val rows: List<TuningRow> get() = TuningCatalog.rows(localize = ::echo)

    // ── dado gerado ────────────────────────────────────────────────────────

    @Test
    fun `catalog has every group and no empty one`() {
        val groups = InstrumentPreset.all.map { it.group }.toSet()
        assertEquals(InstrumentPreset.Group.entries.toSet(), groups)
        assertTrue(InstrumentPreset.all.size >= 45)
    }

    @Test
    fun `a tuning name is either translated or proper`() {
        for (preset in InstrumentPreset.all) {
            assertFalse("${preset.id} tem nameKey e name ao mesmo tempo", preset.nameKey != null && preset.name != null)
        }
    }

    @Test
    fun `every string lands in audible range`() {
        for (preset in InstrumentPreset.all) {
            for (note in preset.strings) {
                val hz = note.frequency()
                assertTrue("${preset.id}: ${note.name}${note.octave} = $hz Hz", hz > 25 && hz < 1400)
            }
        }
    }

    @Test
    fun `researched tunings keep their notes`() {
        val cases = mapOf(
            "guitar-drop-c" to listOf("C", "G", "C", "F", "A", "D"),
            "guitar-dadgad" to listOf("D", "A", "D", "G", "A", "D"),
            "guitar-open-g" to listOf("D", "G", "D", "G", "B", "D"),
            "guitar-bruce-palmer" to listOf("E", "E", "E", "E", "B", "E"),
            "viola-cebolao-re" to listOf("A", "D", "F#", "A", "D"),
            "viola-cebolao-mi" to listOf("B", "E", "G#", "B", "E"),
            "viola-rio-abaixo" to listOf("G", "D", "G", "B", "D"),
            "viola-boiadeira" to listOf("A", "E", "G#", "B", "E"),
            "guitar-7-brazil-c" to listOf("C", "E", "A", "D", "G", "B", "E"),
            "guitar-8-standard" to listOf("F#", "B", "E", "A", "D", "G", "B", "E"),
        )
        for ((id, expected) in cases) {
            assertEquals(id, expected, InstrumentPreset.find(id).strings.map { it.name })
        }
    }

    @Test
    fun `cebolao in D is cebolao in E a whole step down`() {
        val re = InstrumentPreset.find("viola-cebolao-re").strings
        val mi = InstrumentPreset.find("viola-cebolao-mi").strings
        assertEquals(mi.size, re.size)
        for ((a, b) in re.zip(mi)) {
            assertTrue(abs(log2(b.frequency() / a.frequency()) - 2.0 / 12) < 0.0001)
        }
    }

    // ── linhas ─────────────────────────────────────────────────────────────

    @Test
    fun `row shows tuning over family and keeps both`() {
        val dropC = rows.first { it.id == "guitar-drop-c" }
        assertEquals("Drop C", dropC.title)
        assertEquals("guitar", dropC.family)
        assertEquals("C G C F A D", dropC.notes)
        assertEquals("guitar · Drop C", dropC.compactLabel)
        assertEquals("guitar · C G C F A D", dropC.subtitle)
    }

    @Test
    fun `chromatic falls back to the family name`() {
        val chromatic = rows.first { it.id == "chromatic" }
        assertEquals("chromatic", chromatic.title)
        assertTrue(chromatic.notes.isEmpty())
        assertEquals("chromatic", chromatic.compactLabel)
    }

    @Test
    fun `sharps and flats use glyphs not ascii`() {
        val cSharp = rows.first { it.id == "guitar-c-sharp" }
        assertEquals("C♯ F♯ B E G♯ C♯", cSharp.notes)
        val eb = rows.first { it.id == "guitar-eb" }
        assertEquals("E♭ A♭ D♭ G♭ B♭ E♭", eb.notes)
        assertTrue(cSharp.notes.contains("B")) // "B" maiúsculo nunca vira bemol
    }

    // ── busca ──────────────────────────────────────────────────────────────

    @Test
    fun `search ignores accent and case`() {
        assertEquals(2, TuningCatalog.filter(rows, "CEBOLAO").size)
        assertEquals(2, TuningCatalog.filter(rows, "cebolão").size)
        assertEquals(listOf("viola-rio-abaixo"), TuningCatalog.filter(rows, "Rio-Abaixo").map { it.id })
    }

    @Test
    fun `search finds by band notes and instrument`() {
        assertTrue(TuningCatalog.filter(rows, "slipknot").any { it.id == "guitar-drop-b" })
        assertTrue(TuningCatalog.filter(rows, "keith").any { it.id == "guitar-open-g" })
        assertTrue(TuningCatalog.filter(rows, "dadgad").any { it.id == "guitar-dadgad" })
        assertTrue(TuningCatalog.filter(rows, "cgcfad").any { it.id == "guitar-drop-c" })
    }

    @Test
    fun `search terms are anded and one letter does not dig into bands`() {
        val hits = TuningCatalog.filter(rows, "drop b").map { it.id }
        assertTrue(hits.contains("guitar-drop-b"))
        assertFalse(hits.contains("guitar-drop-c"))
        assertTrue(TuningCatalog.filter(rows, "bul").any { it.id == "guitar-drop-c" })
        assertTrue(TuningCatalog.filter(rows, "bu").isEmpty())
    }

    @Test
    fun `empty query keeps everything`() {
        assertEquals(rows.size, TuningCatalog.filter(rows, "").size)
        assertEquals(rows.size, TuningCatalog.filter(rows, "   ").size)
    }

    @Test
    fun `nonsense query finds nothing`() = assertTrue(TuningCatalog.filter(rows, "zzxq").isEmpty())

    @Test
    fun `sharp glyph in the name is still typeable as ascii`() {
        assertEquals("Drop C♯", rows.first { it.id == "guitar-drop-c-sharp" }.title)
        val ascii = TuningCatalog.filter(rows, "drop c#").map { it.id }
        val glyph = TuningCatalog.filter(rows, "drop c♯").map { it.id }
        assertEquals("digitar # ou ♯ tem que dar na mesma", glyph, ascii)
        assertEquals("guitar-drop-c-sharp", ascii.first())
    }

    // ── seções ─────────────────────────────────────────────────────────────

    @Test
    fun `sections follow group order and drop empty ones`() {
        val all = TuningCatalog.sections(rows, ::echo)
        assertEquals(InstrumentPreset.Group.entries.toList(), all.map { it.group })

        val onlyViola = TuningCatalog.filter(rows, "cebolao")
        val sections = TuningCatalog.sections(onlyViola, ::echo)
        assertEquals(listOf(InstrumentPreset.Group.world), sections.map { it.group })
        assertEquals("world", sections.first().title)
    }

    // ── recentes ───────────────────────────────────────────────────────────

    @Test
    fun `recent puts the newest first without repeating`() {
        var recents = emptyList<String>()
        for (id in listOf("guitar-drop-c", "bass-4", "guitar-drop-c")) {
            recents = TuningCatalog.pushRecent(id, recents)
        }
        assertEquals(listOf("guitar-drop-c", "bass-4"), recents)
    }

    @Test
    fun `recent stops at the limit`() {
        var recents = emptyList<String>()
        for (preset in InstrumentPreset.all.take(10)) {
            recents = TuningCatalog.pushRecent(preset.id, recents)
        }
        assertEquals(TuningCatalog.recentLimit, recents.size)
        assertEquals(InstrumentPreset.all[9].id, recents.first())
    }

    @Test
    fun `recent ignores ids that no longer exist`() {
        val found = TuningCatalog.recentRows(rows, listOf("guitar-drop-c", "afinacao-fantasma"))
        assertEquals(listOf("guitar-drop-c"), found.map { it.id })
    }
}
