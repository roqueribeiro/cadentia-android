#!/usr/bin/env python3
"""Gera ChordLibrary.kt a partir do ChordLibrary.swift do cadentia-ios.

Dados, não lógica: os 77 acordes são gerados do iOS (que por sua vez é gerado
do chords.js do roqueos-front), para os três nascerem iguais. Rode apontando
para o clone do iOS:

    python3 scripts/gen-chords.py ../cadentia-ios/Packages/CadentiaKit/Sources/CadentiaKit/ChordLibrary.swift
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "kit" / "src" / "main" / "kotlin" / "com" / "levelhard" / "cadentia" / "kit" / "ChordLibrary.kt"

CHORD = re.compile(
    r'Chord\(id: "(?P<id>[^"]+)", displayName: "(?P<dn>[^"]+)", root: "(?P<root>[^"]+)", '
    r'quality: "(?P<q>[^"]+)", notes: \[(?P<notes>[^\]]*)\], guitarFrets: \[(?P<frets>[^\]]*)\], '
    r'pianoNotes: \[(?P<piano>[^\]]*)\]\)'
)
QUALITY = re.compile(r'Quality\(id: "(?P<id>[^"]+)", label: "(?P<label>[^"]+)"\)')


def kt_list(swift_list: str) -> str:
    return ", ".join(part.strip() for part in swift_list.split(",") if part.strip())


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    src = Path(sys.argv[1]).read_text()

    qualities = QUALITY.findall(src)
    chords = list(CHORD.finditer(src))
    if len(chords) != 77:
        raise SystemExit(f"esperava 77 acordes no Swift, achei {len(chords)}")

    lines = [
        "package com.levelhard.cadentia.kit",
        "",
        "// GERADO por scripts/gen-chords.py a partir do ChordLibrary.swift do",
        "// cadentia-ios (que nasce do chords.js do roqueos-front). Não edite.",
        "",
        "/**",
        " * Biblioteca curada de acordes (77): classes de altura, forma de violão e",
        " * voicing de piano. `guitarFrets` = [E A D G B e]; -1 abafada, 0 solta.",
        " */",
        "data class Chord(",
        "    val id: String,",
        "    val displayName: String,",
        "    val root: String,",
        "    val quality: String,",
        "    val notes: List<String>,",
        "    val guitarFrets: List<Int>,",
        "    /** Nomes com oitava, ex.: [\"C4\", \"E4\", \"G4\"]. */",
        "    val pianoNotes: List<String>,",
        ")",
        "",
        "object ChordLibrary {",
        "    data class Quality(val id: String, val label: String)",
        "",
        "    val qualities: List<Quality> = listOf(",
    ]
    for qid, label in qualities:
        lines.append(f'        Quality("{qid}", "{label}"),')
    lines += [
        "    )",
        "",
        "    /** Ids das qualidades, na ordem — o que o AppSettings.sanitize valida. */",
        "    val qualityIds: List<String> = qualities.map { it.id }",
        "",
        '    val roots = listOf("C", "D", "E", "F", "G", "A", "B")',
        "",
        "    val all: List<Chord> = listOf(",
    ]
    for m in chords:
        lines.append(
            f'        Chord("{m["id"]}", "{m["dn"]}", "{m["root"]}", "{m["q"]}", '
            f'listOf({kt_list(m["notes"])}), listOf({kt_list(m["frets"])}), '
            f'listOf({kt_list(m["piano"])})),'
        )
    lines += [
        "    )",
        "",
        "    fun find(id: String?): Chord? = all.firstOrNull { it.id == id }",
        "",
        "    fun find(root: String, quality: String): Chord? =",
        "        all.firstOrNull { it.root == root && it.quality == quality }",
        "}",
    ]
    OUT.write_text("\n".join(lines) + "\n")
    print(f"Gerado {OUT.name}: {len(chords)} acordes, {len(qualities)} qualidades.")


if __name__ == "__main__":
    main()
