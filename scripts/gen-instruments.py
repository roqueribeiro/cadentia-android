#!/usr/bin/env python3
"""Gera kit/.../InstrumentPreset.kt a partir do InstrumentPreset.swift GERADO do iOS.

A fonte da verdade é o `instruments.js` do roqueos-front; o iOS já o
transcreve com `scripts/gen-instruments.mjs` (validando id duplicado, grupo
desconhecido e nota fora da faixa audível). Aqui lemos o Swift gerado e
emitimos Kotlin com os mesmos ids, grupos, chaves e cordas — um catálogo, dois
alvos, zero transcrição à mão (49 afinações digitadas à mão é como a viola
saiu com as cordas invertidas uma vez).

    python3 scripts/gen-instruments.py ../cadentia-ios/Packages/CadentiaKit/Sources/CadentiaKit/InstrumentPreset.swift
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "kit" / "src" / "main" / "kotlin" / "com" / "levelhard" / "cadentia" / "kit" / "InstrumentPreset.kt"

ENTRY = re.compile(
    r'\.init\(\s*id:\s*"(?P<id>[^"]+)",\s*group:\s*\.(?P<group>\w+),\s*familyKey:\s*"(?P<family>[^"]+)",'
    r'\s*nameKey:\s*(?P<nameKey>nil|"[^"]*"),\s*name:\s*(?P<name>nil|"[^"]*"),\s*artists:\s*"(?P<artists>[^"]*)",'
    r'\s*strings:\s*\[(?P<strings>[^\]]*)\]\s*\)',
    re.S,
)
STRING = re.compile(r'\.init\(name:\s*"(?P<name>[^"]+)",\s*octave:\s*(?P<octave>-?\d+)\)')
GROUPS = ["chromatic", "guitar", "bass", "extended", "world"]
NOTE_NAMES = {"C", "C#", "Db", "D", "D#", "Eb", "E", "F", "F#", "Gb", "G", "G#", "Ab", "A", "A#", "Bb", "B"}


def kstr(swift_literal: str) -> str:
    if swift_literal == "nil":
        return "null"
    return swift_literal.replace("$", "\\$")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    source = Path(sys.argv[1]).read_text()
    entries = list(ENTRY.finditer(source))
    if not entries:
        raise SystemExit("nenhuma afinação encontrada — o formato do Swift gerado mudou?")

    seen: set[str] = set()
    lines = []
    for m in entries:
        pid = m.group("id")
        if pid in seen:
            raise SystemExit(f"id duplicado: {pid}")
        seen.add(pid)
        if m.group("group") not in GROUPS:
            raise SystemExit(f"grupo desconhecido em {pid}: {m.group('group')}")
        if m.group("nameKey") != "nil" and m.group("name") != "nil":
            raise SystemExit(f"{pid} tem nameKey e name ao mesmo tempo")
        strings = []
        for s in STRING.finditer(m.group("strings")):
            if s.group("name") not in NOTE_NAMES:
                raise SystemExit(f"nota desconhecida em {pid}: {s.group('name')}")
            strings.append(f'StringNote("{s.group("name")}", {s.group("octave")})')
        strings_kt = "emptyList()" if not strings else "listOf(" + ", ".join(strings) + ")"
        lines.append(
            "            InstrumentPreset(\n"
            f'                id = "{pid}", group = Group.{m.group("group")},\n'
            f'                familyKey = "{m.group("family")}",\n'
            f'                nameKey = {kstr(m.group("nameKey"))}, name = {kstr(m.group("name"))},\n'
            f'                artists = "{m.group("artists")}",\n'
            f"                strings = {strings_kt},\n"
            "            ),"
        )

    body = "\n".join(lines)
    OUT.write_text(f'''// GERADO por scripts/gen-instruments.py a partir do InstrumentPreset.swift do
// cadentia-ios (que por sua vez é gerado do instruments.js do roqueos-front).
// Não edite na mão: regenere.
package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.log2

/**
 * Uma afinação do catálogo — {len(entries)} no total, mesmos ids da PWA para
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
) {{
    data class StringNote(val name: String, val octave: Int) {{
        fun frequency(referenceA: Double = 440.0): Double =
            MusicNotes.frequency(name, octave, referenceA) ?: 0.0

        /** "F♯" — glifo musical, não ASCII. */
        val display: String
            get() = name.replace("#", "♯").replace("b", "♭")
    }}

    /** Seção do seletor. A ordem dos casos é a ordem em que aparecem. */
    enum class Group(val id: String) {{
        chromatic("chromatic"), guitar("guitar"), bass("bass"), extended("extended"), world("world");

        val nameKey: String get() = "music.tuner.groups.$id"
    }}

    val stringCount: Int get() = strings.size

    /** "D A D G B E", com glifos. */
    val notesLine: String get() = strings.joinToString(" ") {{ it.display }}

    data class NearestString(val note: StringNote, val frequency: Double)

    /** Corda mais próxima da frequência detectada (distância log), null no cromático. */
    fun nearestString(hz: Double, referenceA: Double = 440.0): NearestString? {{
        if (hz <= 0 || strings.isEmpty()) return null
        var closest: NearestString? = null
        var minDist = Double.POSITIVE_INFINITY
        for (s in strings) {{
            val f = s.frequency(referenceA)
            if (f <= 0) continue
            val dist = abs(log2(hz / f))
            if (dist < minDist) {{
                minDist = dist
                closest = NearestString(s, f)
            }}
        }}
        return closest
    }}

    companion object {{
        fun find(id: String?): InstrumentPreset = all.firstOrNull {{ it.id == id }} ?: all[0]

        val all: List<InstrumentPreset> = listOf(
{body}
        )
    }}
}}
''')
    print(f"Gerado {OUT.relative_to(REPO)} com {len(entries)} afinações.")


if __name__ == "__main__":
    main()
