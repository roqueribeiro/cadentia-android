#!/usr/bin/env python3
"""Gera app/src/main/res/values*/strings.xml a partir de i18n/Localizable.xcstrings.

O catálogo do iOS é a fonte da paridade: mesma chave, mesmo texto, nos mesmos
10 idiomas. pt-BR é o canônico e vai em values/ (fallback do Android); os
outros nove vão em values-<locale>/.

Regras herdadas do gerador do iOS:
- Toda chave precisa existir nos 10 idiomas, senão o gerador FALHA.
- O gerador RECUSA escrever quando uma chave que existia sumiria do strings.xml
  (proteção contra regressão silenciosa). Remoção deliberada: --allow-removals.
"""

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CATALOG = REPO / "i18n" / "Localizable.xcstrings"
RES = REPO / "app" / "src" / "main" / "res"

# xcstrings locale -> pasta de resources do Android. pt-BR canônico no default.
LOCALE_DIRS = {
    "pt-BR": "values",
    "en": "values-en",
    "ar": "values-ar",
    "de": "values-de",
    "es": "values-es",
    "fr": "values-fr",
    "hi": "values-hi",
    "ja": "values-ja",
    "ru": "values-ru",
    "zh-Hans": "values-zh-rCN",
}
REQUIRED_LOCALES = set(LOCALE_DIRS)


def key_to_res(key: str) -> str:
    """music.tuner.instruments.guitarDropD -> music_tuner_instruments_guitar_drop_d"""
    out = []
    for ch in key:
        if ch in ".-":
            out.append("_")
        elif ch.isupper():
            out.append("_" + ch.lower())
        else:
            out.append(ch)
    name = "".join(out)
    name = re.sub(r"_+", "_", name).strip("_")
    if not re.fullmatch(r"[a-z][a-z0-9_]*", name):
        raise SystemExit(f"chave gera nome de resource inválido: {key!r} -> {name!r}")
    return name


def android_escape(value: str) -> str:
    """Escapa para o formato de string resource do Android."""
    v = value.replace("\\", "\\\\")
    v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    v = v.replace("'", "\\'").replace('"', '\\"')
    v = v.replace("\n", "\\n").replace("\t", "\\t")
    if v.startswith("@") or v.startswith("?"):
        v = "\\" + v
    # Placeholders do iOS -> java format (hoje o catálogo não tem nenhum;
    # se um dia entrar, %@ vira %s e um % literal solto vira %%).
    v = v.replace("%@", "%s").replace("%lld", "%d")
    v = re.sub(r"%(?![%0-9sdf])", "%%", v)
    return v


def load_catalog() -> dict[str, dict[str, str]]:
    data = json.loads(CATALOG.read_text())
    strings = data["strings"]
    missing = []
    table: dict[str, dict[str, str]] = {}
    for key, entry in sorted(strings.items()):
        locs = entry.get("localizations", {})
        absent = REQUIRED_LOCALES - set(locs)
        if absent:
            missing.append(f"  {key}: falta {sorted(absent)}")
            continue
        table[key] = {
            lc: locs[lc]["stringUnit"]["value"] for lc in REQUIRED_LOCALES
        }
    if missing:
        print("Catálogo incompleto — toda chave entra nos 10 idiomas ou não entra:")
        print("\n".join(missing))
        sys.exit(1)
    return table


def existing_names(path: Path) -> set[str]:
    if not path.exists():
        return set()
    return set(re.findall(r'<string name="([a-z0-9_]+)"', path.read_text()))


def main() -> None:
    allow_removals = "--allow-removals" in sys.argv
    check_only = "--check" in sys.argv
    table = load_catalog()

    names = {}
    for key in table:
        name = key_to_res(key)
        if name in names:
            raise SystemExit(f"colisão de nome: {key!r} e {names[name]!r} -> {name}")
        names[name] = key

    for locale, dirname in LOCALE_DIRS.items():
        out_dir = RES / dirname
        out_path = out_dir / "strings.xml"

        lines = ['<?xml version="1.0" encoding="utf-8"?>']
        lines.append("<!-- GERADO por scripts/gen-i18n.py a partir de i18n/Localizable.xcstrings.")
        lines.append("     Não edite na mão: mude o catálogo (a fonte é o iOS) e regenere. -->")
        lines.append("<resources>")
        if locale == "pt-BR":
            lines.append('    <string name="app_name" translatable="false">Cadentia</string>')
        for name in sorted(names):
            value = android_escape(table[names[name]][locale])
            lines.append(f'    <string name="{name}">{value}</string>')
        lines.append("</resources>")
        content = "\n".join(lines) + "\n"

        old = existing_names(out_path)
        new = set(names)
        if locale == "pt-BR":
            new.add("app_name")
        gone = old - new
        if gone and not allow_removals:
            print(f"RECUSADO ({dirname}): estas chaves sumiriam: {sorted(gone)[:10]}")
            print("Se a remoção é deliberada, rode com --allow-removals.")
            sys.exit(1)

        if check_only:
            if not out_path.exists() or out_path.read_text() != content:
                print(f"DESATUALIZADO: {out_path.relative_to(REPO)} difere do catálogo. Rode scripts/gen-i18n.py.")
                sys.exit(1)
            continue

        out_dir.mkdir(parents=True, exist_ok=True)
        out_path.write_text(content)

    if check_only:
        print(f"i18n em dia: {len(names)} chaves × {len(LOCALE_DIRS)} idiomas.")
    else:
        print(f"Gerado: {len(names)} chaves × {len(LOCALE_DIRS)} idiomas.")


if __name__ == "__main__":
    main()
