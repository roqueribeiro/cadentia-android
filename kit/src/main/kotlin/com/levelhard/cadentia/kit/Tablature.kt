package com.levelhard.cadentia.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * O modelo do documento .rostab — port fiel do `Tablature.swift` (que porta
 * o `tablatureModel.js` do web, formato "rostab", versão 2). Arquivo criado
 * no RoqueOS web abre aqui sem mudar, e vice-versa: o parse é tolerante
 * exatamente onde o web é (célula numérica legada, campo opcional ausente) e
 * a serialização derruba defaults do mesmo jeito — round-trip estável.
 */
data class Tablature(
    var meta: Meta = Meta(),
    var transport: Transport = Transport(),
    var masterFx: MasterFx = MasterFx(),
    var repeatBlocks: MutableList<RepeatBlock> = mutableListOf(),
    var chordMarks: MutableList<ChordMark> = mutableListOf(),
    var tracks: MutableList<Track> = mutableListOf(),
) {
    companion object {
        const val FORMAT = "rostab"
        const val VERSION = 2
        const val DEFAULT_STEPS_PER_MEASURE = 16
        const val DEFAULT_BPM = 120
    }

    data class Meta(
        var title: String = "Untitled",
        var author: String = "",
        var createdAt: String = "",
        var updatedAt: String = "",
    )

    data class Transport(
        var bpm: Int = DEFAULT_BPM,
        var timeSignature: List<Int> = listOf(4, 4),
    )

    data class MasterFx(
        var reverbMix: Double = 0.0,
        var delayMix: Double = 0.0,
        var delayTime: Double = 400.0,
        var delayFeedback: Double = 0.4,
    )

    data class RepeatBlock(
        var id: String = "",
        var startIdx: Int = 0,
        var endIdx: Int = 0,
        /** -1 = infinito. */
        var count: Int = 2,
    )

    data class ChordMark(
        var id: String = "",
        var measureIdx: Int = 0,
        var col: Int = 0,
        var chordId: String = "",
        var displayName: String = "",
    )

    /**
     * Uma célula de nota: `v` é casa (guitar/bass), MIDI absoluto (keys SATB)
     * ou 1 (batida de drums). `dur` em semicolcheias; `tup == 3` = quiáltera.
     */
    data class Cell(
        var v: Int,
        var dur: Int = 1,
        var tup: Int? = null,
        /** Flags de articulação (pm/ho/po/slide…), verbatim no round-trip. */
        var articulations: Map<String, Boolean> = emptyMap(),
    ) {
        /** Duração efetiva em semicolcheias (quiáltera = ×2/3). */
        val effectiveDuration: Double
            get() {
                val base = maxOf(dur, 1).toDouble()
                return if (tup == 3) base * 2 / 3 else base
            }
    }

    data class StringLine(
        var stringIndex: Int = 0,
        var steps: MutableList<Cell?> = mutableListOf(),
    )

    data class Measure(
        var stepsPerMeasure: Int = DEFAULT_STEPS_PER_MEASURE,
        /** 1 = toca uma vez; -1 = infinito (sentinela do web). */
        var repeats: Int = 1,
        /** Override opcional por compasso do transport.timeSignature. */
        var timeSignature: List<Int>? = null,
        var strings: MutableList<StringLine> = mutableListOf(),
    )

    data class RowMeta(
        var label: String = "",
        var padId: String? = null,
        var stringIndex: Int = 0,
        var baseMidi: Int? = null,
        var hand: String? = null,
    )

    data class TuningString(var name: String = "E", var octave: Int = 2)

    data class Track(
        var id: String = "",
        var name: String = "",
        /** guitar | bass | drums | keys */
        var type: String = "guitar",
        var instrumentId: String? = null,
        var tuning: MutableList<TuningString> = mutableListOf(),
        var kitId: String? = null,
        var voiceId: String? = null,
        var volume: Double = 0.8,
        var muted: Boolean = false,
        var soloed: Boolean = false,
        var rowsMeta: MutableList<RowMeta> = mutableListOf(),
        var measures: MutableList<Measure> = mutableListOf(),
    ) {
        val rowCount: Int
            get() = if (rowsMeta.isEmpty()) (measures.firstOrNull()?.strings?.size ?: 0) else rowsMeta.size

        /** Valor da célula → MIDI (o `trackCellToMidi` do web). Drums resolvem por padId. */
        fun midi(rowIdx: Int, value: Int): Int? {
            if (type == "drums") return null
            if (type == "keys") {
                // Linhas SATB carregam baseMidi (valor é MIDI absoluto); trilha
                // keys legada guarda offset de semitons a partir do C4.
                return if (rowIdx in rowsMeta.indices && rowsMeta[rowIdx].baseMidi != null) {
                    value
                } else {
                    60 + value
                }
            }
            val open = tuning.getOrNull(rowIdx) ?: return null
            val base = MusicNotes.noteToMidi(open.name, open.octave) ?: return null
            return base + value
        }

        fun padId(rowIdx: Int): String? {
            if (type != "drums") return null
            return rowsMeta.getOrNull(rowIdx)?.padId
        }

        /** Primeira coluna absoluta de um compasso (colunas = passos somados). */
        fun measureStartColumn(measureIdx: Int): Int =
            measures.take(measureIdx).sumOf { it.stepsPerMeasure }

        val totalColumns: Int get() = measures.sumOf { it.stepsPerMeasure }
    }

    // MARK: plano de reprodução (port do buildPlaybackPlan v5.1)

    data class PlanEntry(val measureIdx: Int, val stepIdx: Int)

    data class PlaybackPlan(
        val entries: List<PlanEntry>,
        /** Quando não-nulos, a reprodução repete entries[infiniteFrom..<infiniteTo] para sempre. */
        val infiniteFrom: Int?,
        val infiniteTo: Int?,
    ) {
        /** Entrada para um índice global de batida (null = passou do fim, sem infinito). */
        fun entryAtBeat(beat: Int): PlanEntry? {
            val from = infiniteFrom
            val to = infiniteTo
            if (from != null && to != null && to > from && beat >= from) {
                return entries[from + (beat - from) % (to - from)]
            }
            return entries.getOrNull(beat)
        }
    }

    fun playbackPlan(track: Track): PlaybackPlan {
        val entries = mutableListOf<PlanEntry>()
        var infiniteFrom: Int? = null
        var infiniteTo: Int? = null
        var halted = false

        fun push(measureIdx: Int, measure: Measure, iterations: Int) {
            repeat(iterations) {
                for (step in 0 until measure.stepsPerMeasure) {
                    entries.add(PlanEntry(measureIdx, step))
                }
            }
        }

        var i = 0
        while (i < track.measures.size && !halted) {
            val block = repeatBlocks.firstOrNull { it.startIdx == i && it.endIdx < track.measures.size }
            if (block != null) {
                val infiniteBlock = block.count == -1
                val iterations = if (infiniteBlock) 1 else block.count
                val blockStart = entries.size
                outer@ for (pass in 0 until maxOf(iterations, 1)) {
                    for (j in block.startIdx..block.endIdx) {
                        val measure = track.measures[j]
                        if (measure.repeats == -1) {
                            infiniteFrom = entries.size
                            push(j, measure, iterations = 1)
                            infiniteTo = entries.size
                            halted = true
                            break@outer
                        }
                        push(j, measure, iterations = maxOf(1, measure.repeats))
                    }
                }
                if (infiniteBlock && !halted) {
                    infiniteFrom = blockStart
                    infiniteTo = entries.size
                    halted = true
                }
                i = block.endIdx + 1
            } else {
                val measure = track.measures[i]
                if (measure.repeats == -1) {
                    infiniteFrom = entries.size
                    push(i, measure, iterations = 1)
                    infiniteTo = entries.size
                    halted = true
                } else {
                    push(i, measure, iterations = maxOf(1, measure.repeats))
                }
                i += 1
            }
        }
        return PlaybackPlan(entries, infiniteFrom, infiniteTo)
    }

    // MARK: serialização (espelha serializeRostab: fx/blocos incondicionais,
    // células compactas com defaults derrubados)

    fun serialize(): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put(
                "meta",
                buildJsonObject {
                    put("title", meta.title)
                    put("author", meta.author)
                    put("createdAt", meta.createdAt)
                    put("updatedAt", meta.updatedAt)
                },
            )
            put(
                "transport",
                buildJsonObject {
                    put("bpm", transport.bpm)
                    put("timeSignature", JsonArray(transport.timeSignature.map { JsonPrimitive(it) }))
                },
            )
            put(
                "masterFx",
                buildJsonObject {
                    put("reverbMix", masterFx.reverbMix)
                    put("delayMix", masterFx.delayMix)
                    put("delayTime", masterFx.delayTime)
                    put("delayFeedback", masterFx.delayFeedback)
                },
            )
            put(
                "repeatBlocks",
                buildJsonArray {
                    for (block in repeatBlocks) {
                        add(
                            buildJsonObject {
                                put("id", block.id)
                                put("startIdx", block.startIdx)
                                put("endIdx", block.endIdx)
                                put("count", block.count)
                            },
                        )
                    }
                },
            )
            put(
                "chordMarks",
                buildJsonArray {
                    for (mark in chordMarks) {
                        add(
                            buildJsonObject {
                                put("id", mark.id)
                                put("measureIdx", mark.measureIdx)
                                put("col", mark.col)
                                put("chordId", mark.chordId)
                                put("displayName", mark.displayName)
                            },
                        )
                    }
                },
            )
            put(
                "tracks",
                buildJsonArray {
                    for (track in tracks) {
                        add(serializeTrack(track))
                    }
                },
            )
        }
        return Json.encodeToString(JsonObject.serializer(), root)
    }

    private fun serializeTrack(track: Track): JsonObject = buildJsonObject {
        put("id", track.id)
        put("name", track.name)
        put("type", track.type)
        put(
            "tuning",
            buildJsonArray {
                for (s in track.tuning) {
                    add(
                        buildJsonObject {
                            put("name", s.name)
                            put("octave", s.octave)
                        },
                    )
                }
            },
        )
        put("volume", track.volume)
        put("muted", track.muted)
        put("soloed", track.soloed)
        put(
            "rowsMeta",
            buildJsonArray {
                for (meta in track.rowsMeta) {
                    add(
                        buildJsonObject {
                            put("label", meta.label)
                            put("stringIndex", meta.stringIndex)
                            meta.padId?.let { put("padId", it) }
                            meta.baseMidi?.let { put("baseMidi", it) }
                            meta.hand?.let { put("hand", it) }
                        },
                    )
                }
            },
        )
        put(
            "measures",
            buildJsonArray {
                for (measure in track.measures) {
                    add(
                        buildJsonObject {
                            put("stepsPerMeasure", measure.stepsPerMeasure)
                            if (measure.repeats > 1 || measure.repeats == -1) put("repeats", measure.repeats)
                            measure.timeSignature?.let { ts ->
                                put("timeSignature", JsonArray(ts.map { JsonPrimitive(it) }))
                            }
                            put(
                                "strings",
                                buildJsonArray {
                                    for (line in measure.strings) {
                                        add(
                                            buildJsonObject {
                                                put("stringIndex", line.stringIndex)
                                                put(
                                                    "steps",
                                                    buildJsonArray {
                                                        for (cell in line.steps) {
                                                            if (cell == null) {
                                                                add(JsonNull)
                                                            } else {
                                                                add(
                                                                    buildJsonObject {
                                                                        put("v", cell.v)
                                                                        val flags = cell.articulations.filterValues { it }
                                                                        if (flags.isNotEmpty()) {
                                                                            put(
                                                                                "a",
                                                                                buildJsonObject {
                                                                                    for ((key, value) in flags) put(key, value)
                                                                                },
                                                                            )
                                                                        }
                                                                        if (cell.dur > 1) put("dur", cell.dur)
                                                                        if (cell.tup == 3) put("tup", 3)
                                                                    },
                                                                )
                                                            }
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        track.instrumentId?.let { put("instrumentId", it) }
        track.kitId?.let { put("kitId", it) }
        track.voiceId?.let { put("voiceId", it) }
    }
}

/** Erros do parse — espelho do `ParseError` do iOS. */
sealed class RostabParseException(message: String) : Exception(message) {
    class WrongFormat : RostabParseException("missing or wrong \"format\" field")
    class UnsupportedVersion(version: Int) :
        RostabParseException("unsupported .rostab version $version (expected 2)")
    class Invalid(reason: String) : RostabParseException("invalid .rostab document: $reason")
}

object RostabParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Tablature {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw RostabParseException.Invalid("not a JSON object")
        }
        if (root.str("format") != Tablature.FORMAT) throw RostabParseException.WrongFormat()
        val fileVersion = root.int("version") ?: 0
        if (fileVersion != Tablature.VERSION) throw RostabParseException.UnsupportedVersion(fileVersion)
        val rawTracks = root["tracks"] as? JsonArray
        if (rawTracks == null || rawTracks.isEmpty()) {
            throw RostabParseException.Invalid("tracks must be a non-empty array")
        }

        val tab = Tablature()
        (root["meta"] as? JsonObject)?.let { meta ->
            tab.meta.title = meta.str("title") ?: "Untitled"
            tab.meta.author = meta.str("author") ?: ""
            tab.meta.createdAt = meta.str("createdAt") ?: ""
            tab.meta.updatedAt = meta.str("updatedAt") ?: ""
        }
        (root["transport"] as? JsonObject)?.let { transport ->
            tab.transport.bpm = transport.int("bpm") ?: Tablature.DEFAULT_BPM
            val ts = (transport["timeSignature"] as? JsonArray)?.mapNotNull { it.intOrNullSafe() }
            if (ts != null && ts.size == 2) tab.transport.timeSignature = ts
        }
        (root["masterFx"] as? JsonObject)?.let { fx ->
            tab.masterFx.reverbMix = fx.double("reverbMix") ?: 0.0
            tab.masterFx.delayMix = fx.double("delayMix") ?: 0.0
            tab.masterFx.delayTime = fx.double("delayTime") ?: 400.0
            tab.masterFx.delayFeedback = fx.double("delayFeedback") ?: 0.4
        }
        tab.repeatBlocks = ((root["repeatBlocks"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { raw ->
                val obj = raw as? JsonObject ?: return@mapNotNull null
                val start = obj.int("startIdx") ?: return@mapNotNull null
                val end = obj.int("endIdx") ?: return@mapNotNull null
                Tablature.RepeatBlock(
                    id = obj.str("id") ?: UUID.randomUUID().toString(),
                    startIdx = start,
                    endIdx = end,
                    count = obj.int("count") ?: 2,
                )
            }.toMutableList()
        tab.chordMarks = ((root["chordMarks"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { raw ->
                val obj = raw as? JsonObject ?: return@mapNotNull null
                val chordId = obj.str("chordId") ?: ""
                Tablature.ChordMark(
                    id = obj.str("id") ?: UUID.randomUUID().toString(),
                    measureIdx = obj.int("measureIdx") ?: 0,
                    col = obj.int("col") ?: 0,
                    chordId = chordId,
                    displayName = obj.str("displayName") ?: chordId,
                )
            }.toMutableList()
        tab.tracks = rawTracks.mapNotNull { it as? JsonObject }.map(::parseTrack).toMutableList()
        return tab
    }

    private fun parseTrack(raw: JsonObject): Tablature.Track {
        val track = Tablature.Track()
        track.id = raw.str("id") ?: UUID.randomUUID().toString()
        track.type = raw.str("type") ?: "guitar"
        track.name = raw.str("name") ?: track.type.replaceFirstChar { it.uppercase() }
        track.instrumentId = raw.str("instrumentId")
        track.kitId = raw.str("kitId")
        track.voiceId = raw.str("voiceId")
        track.volume = raw.double("volume") ?: 0.8
        track.muted = raw.bool("muted") ?: false
        track.soloed = raw.bool("soloed") ?: false
        track.tuning = ((raw["tuning"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { s ->
                val obj = s as? JsonObject ?: return@mapNotNull null
                val name = obj.str("name") ?: return@mapNotNull null
                Tablature.TuningString(name, obj.int("octave") ?: 2)
            }.toMutableList()
        track.rowsMeta = ((raw["rowsMeta"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .map { m ->
                Tablature.RowMeta(
                    label = m.str("label") ?: "",
                    padId = m.str("padId"),
                    stringIndex = m.int("stringIndex") ?: 0,
                    baseMidi = m.int("baseMidi"),
                    hand = m.str("hand"),
                )
            }.toMutableList()
        track.measures = ((raw["measures"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .map { m ->
                val measure = Tablature.Measure()
                measure.stepsPerMeasure = m.int("stepsPerMeasure") ?: Tablature.DEFAULT_STEPS_PER_MEASURE
                measure.repeats = m.int("repeats") ?: 1
                val ts = (m["timeSignature"] as? JsonArray)?.mapNotNull { it.intOrNullSafe() }
                if (ts != null && ts.size == 2) measure.timeSignature = ts
                measure.strings = ((m["strings"] as? JsonArray) ?: JsonArray(emptyList()))
                    .mapNotNull { it as? JsonObject }
                    .map { s ->
                        Tablature.StringLine(
                            stringIndex = s.int("stringIndex") ?: 0,
                            steps = ((s["steps"] as? JsonArray) ?: JsonArray(emptyList()))
                                .map(::parseCell).toMutableList(),
                        )
                    }.toMutableList()
                measure
            }.toMutableList()
        return track
    }

    /** Células são `null | número (forma curta legada v1) | {v, a?, dur?, tup?}`. */
    private fun parseCell(raw: JsonElement): Tablature.Cell? {
        if (raw is JsonNull) return null
        if (raw is JsonPrimitive) {
            val v = raw.intOrNull ?: return null
            return Tablature.Cell(v = v)
        }
        val obj = raw as? JsonObject ?: return null
        val v = obj.int("v") ?: return null
        val cell = Tablature.Cell(v = v)
        obj.int("dur")?.let { if (it > 0) cell.dur = it }
        if (obj.int("tup") == 3) cell.tup = 3
        (obj["a"] as? JsonObject)?.let { arts ->
            cell.articulations = arts.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.booleanOrNull?.let { key to it }
            }.toMap()
        }
        return cell
    }

    // Leitura tolerante de campos.
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonElement.intOrNullSafe(): Int? = (this as? JsonPrimitive)?.intOrNull
}
