#!/usr/bin/env python3
"""Gera BackingTrackCatalog.kt (os 48 templates) do BackingTrackCatalog.swift.

Só os DADOS: a lógica de montagem (levada por gênero, linha de baixo, build)
vive em BackingTrackBuild.kt, escrita à mão. Rode apontando para o iOS:

    python3 scripts/gen-backing-tracks.py \
        ../cadentia-ios/Packages/CadentiaKit/Sources/CadentiaKit/BackingTrackCatalog.swift
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "kit" / "src" / "main" / "kotlin" / "com" / "levelhard" / "cadentia" / "kit" / "BackingTrackCatalog.kt"

TRACK = re.compile(
    r'BackingTrack\(id: "(?P<id>[^"]+)", nameKey: "(?P<nk>[^"]+)", genre: "(?P<g>[^"]+)", '
    r'key: "(?P<k>[^"]+)", scaleType: (?P<st>"[^"]+"|nil), bpm: (?P<bpm>\d+), '
    r'measureCount: (?P<mc>\d+), timeSignature: \[(?P<ts>[^\]]+)\], '
    r'chordProgression: \[(?P<cp>[^\]]*)\], drumPatternId: "(?P<dp>[^"]+)"\)'
)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    src = Path(sys.argv[1]).read_text()
    tracks = list(TRACK.finditer(src))
    if len(tracks) != 48:
        raise SystemExit(f"esperava 48 bases no Swift, achei {len(tracks)}")

    lines = [
        "package com.levelhard.cadentia.kit",
        "",
        "// GERADO por scripts/gen-backing-tracks.py a partir do",
        "// BackingTrackCatalog.swift do cadentia-ios. Não edite: os dados são a",
        "// fonte da verdade DO APP (decisão do founder registrada no iOS) e a",
        "// lógica de montagem vive em BackingTrackBuild.kt.",
        "",
        "/**",
        " * Template declarativo de base: progressão de acordes (um por compasso)",
        " * + um groove. `build()` (em BackingTrackBuild.kt) monta a tablatura.",
        " */",
        "data class BackingTrack(",
        "    val id: String,",
        "    val nameKey: String,",
        "    val genre: String,",
        "    val key: String,",
        "    val scaleType: String?,",
        "    val bpm: Int,",
        "    val measureCount: Int,",
        "    val timeSignature: List<Int>,",
        "    val chordProgression: List<String>,",
        "    val drumPatternId: String,",
        ") {",
        "    companion object {",
        '        val genres = listOf("rock", "blues", "jazz", "funk", "bossa", "pop", "latin", "electronic")',
        "",
        "        fun byGenre(genre: String): List<BackingTrack> = all.filter { it.genre == genre }",
        "",
        "        val all: List<BackingTrack> = listOf(",
    ]
    for m in tracks:
        st = "null" if m["st"] == "nil" else m["st"]
        chords = ", ".join(part.strip() for part in m["cp"].split(",") if part.strip())
        ts = ", ".join(part.strip() for part in m["ts"].split(","))
        lines.append(
            f'            BackingTrack("{m["id"]}", "{m["nk"]}", "{m["g"]}", "{m["k"]}", {st}, '
            f'{m["bpm"]}, {m["mc"]}, listOf({ts}), listOf({chords}), "{m["dp"]}"),'
        )
    lines += [
        "        )",
        "    }",
        "}",
    ]
    OUT.write_text("\n".join(lines) + "\n")
    print(f"Gerado {OUT.name}: {len(tracks)} bases.")


if __name__ == "__main__":
    main()
