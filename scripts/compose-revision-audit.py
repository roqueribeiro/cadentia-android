#!/usr/bin/env python3
"""Toda composable que recebe um MODELO MUTÁVEL do :kit precisa receber `revision`.

O padrão do app é modelo mutado no lugar + contador `revision` que força a
recomposição. Com o strong skipping do compilador Compose (padrão desde o
Kotlin 2.0.20), parâmetro instável é comparado por INSTÂNCIA: a mesma instância
mutada é "igual", a composable é pulada e a tela fica velha — foi assim que o
Gravador gravou um take e a lane continuou vazia no emulador. Este audit
bloqueia o gate quando alguém esquece de passar `revision` adiante.
"""

import glob
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Classes (ou prefixos) do :kit e do app que são mutadas no lugar.
MUTABLE = [
    "RecorderProject",
    "Setlist",
    "Setlists",
    "Tablature",
    "RecentSongs",
    "SetQueue",
    "StemPlayerEngine.Track",
]

SIGNATURE = re.compile(
    r"@Composable\s*(?:private |internal )?fun\s+(\w+)\s*\((.*?)\)\s*(?:\{|=)", re.S
)


def param_types(params: str) -> list[str]:
    return re.findall(r":\s*([\w\.<>?, ]+?)\s*(?:,|=|$)", params)


def main() -> int:
    offenders = []
    for path in sorted(glob.glob(str(REPO / "app/src/main/java/**/*.kt"), recursive=True)):
        source = Path(path).read_text()
        for match in SIGNATURE.finditer(source):
            name, params = match.group(1), match.group(2)
            hits = sorted(
                {
                    t
                    for t in param_types(params)
                    if any(re.search(r"\b" + re.escape(k) + r"\b", t) for k in MUTABLE)
                }
            )
            if hits and not re.search(r"\brevision\s*:", params):
                offenders.append(f"{Path(path).relative_to(REPO)}: {name}({', '.join(hits)})")
    if offenders:
        print("compose-revision-audit REPROVOU — composable com modelo mutável sem `revision`:")
        for line in offenders:
            print("  " + line)
        return 1
    print("compose-revision-audit ok: toda composable com modelo mutável recebe `revision`.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
