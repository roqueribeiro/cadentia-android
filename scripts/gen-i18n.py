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
    # Placeholders do iOS -> java format: %@ vira %s, %lld/%ld viram %d — também
    # nas formas posicionais (%1$lld, %2$@), que o catálogo da 1.16 usa em seis
    # chaves ("%1$lld de %2$lld"). Sem isto o Java lança
    # UnknownFormatConversionException em "%1$l" na hora de formatar.
    v = re.sub(r"%(\d+\$)?@", lambda m: f"%{m.group(1) or ''}s", v)
    v = re.sub(r"%(\d+\$)?l{1,2}d", lambda m: f"%{m.group(1) or ''}d", v)
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
    apply_android_overrides(table)
    return table


OVERRIDES = REPO / "i18n" / "android-overrides.json"


def apply_android_overrides(table: dict[str, dict[str, str]]) -> None:
    """Ajustes só de plataforma (iPhone → aparelho) por trecho, chave e idioma.

    Falha alto se o trecho não existir: um texto que mudou na fonte não pode
    carregar um ajuste velho em silêncio.
    """
    if not OVERRIDES.exists():
        return
    problems = []
    for item in json.loads(OVERRIDES.read_text())["overrides"]:
        key, locale = item["key"], item["locale"]
        current = table.get(key, {}).get(locale)
        if current is None:
            problems.append(f"  {key}/{locale}: chave ou idioma inexistente no catálogo")
            continue
        if item["find"] not in current:
            problems.append(f"  {key}/{locale}: trecho {item['find']!r} não está mais no texto")
            continue
        table[key][locale] = current.replace(item["find"], item["replace"])
    if problems:
        print("android-overrides.json desatualizado em relação ao catálogo:")
        print("\n".join(problems))
        sys.exit(1)


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

    # O mapa chave→R.string para labels que chegam do :kit como chave do web
    # ("music.drums.pads.kick"). Gerado junto para nunca divergir do catálogo.
    kt_path = REPO / "app" / "src" / "main" / "java" / "com" / "levelhard" / "cadentia" / "I18nMap.kt"
    kt_lines = [
        "package com.levelhard.cadentia",
        "",
        "/** GERADO por scripts/gen-i18n.py — chave do catálogo -> R.string. Não edite. */",
        "object I18nMap {",
        "    val byKey: Map<String, Int> = mapOf(",
    ]
    for name in sorted(names):
        kt_lines.append(f'        "{names[name]}" to R.string.{name},')
    kt_lines.append("    )")
    kt_lines.append("")
    kt_lines.append("    /** Falha alto em chave desconhecida: chave errada é bug de port, não fallback. */")
    kt_lines.append('    fun res(key: String): Int = requireNotNull(byKey[key]) { "chave i18n desconhecida: $key" }')
    kt_lines.append("}")
    kt_content = "\n".join(kt_lines) + "\n"

    if check_only:
        if not kt_path.exists() or kt_path.read_text() != kt_content:
            print("DESATUALIZADO: I18nMap.kt difere do catálogo. Rode scripts/gen-i18n.py.")
            sys.exit(1)
        print(f"i18n em dia: {len(names)} chaves × {len(LOCALE_DIRS)} idiomas.")
    else:
        kt_path.write_text(kt_content)
        print(f"Gerado: {len(names)} chaves × {len(LOCALE_DIRS)} idiomas + I18nMap.kt.")


if __name__ == "__main__":
    main()
