#!/usr/bin/env python3
"""Gera app/src/main/assets/roqueos-config.properties do .env do roqueos-front.

Espelho do scripts/gen-roqueos-config.mjs do cadentia-ios: a chave do Firebase
é a CHAVE DE CLIENTE (a mesma do bundle JS do site) — identifica o projeto,
não autoriza nada. Mesmo assim fica fora do git (o arquivo gerado é ignorado).

    python3 scripts/gen-roqueos-config.py [~/workspaces/roqueos-repos/roqueos-front/.env]
"""

import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "app" / "src" / "main" / "assets" / "roqueos-config.properties"

# As mesmas chaves que o gen-roqueos-config.mjs do iOS aceita, na mesma ordem
# de preferência: o .env do roqueos-front usa FIREBASE_*, sem o prefixo VITE_.
SOURCES = {
    "projectID": ("FIREBASE_PROJECT_ID", "VITE_FIREBASE_PROJECT_ID"),
    "apiKey": ("FIREBASE_API_KEY", "VITE_FIREBASE_API_KEY"),
    "functionsRegion": ("FIREBASE_FUNCTIONS_REGION", "VITE_FUNCTIONS_REGION"),
    "webBaseURL": ("WEB_BASE_URL", "VITE_WEB_BASE_URL"),
}
DEFAULTS = {"projectID": "roqueos", "functionsRegion": "southamerica-east1"}


def main() -> None:
    env = Path(sys.argv[1]) if len(sys.argv) == 2 else Path.home() / "workspaces/roqueos-repos/roqueos-front/.env"
    if not env.is_file():
        raise SystemExit(f".env do roqueos-front nao encontrado: {env}")
    raw: dict[str, str] = {}
    for line in env.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        raw[key.strip()] = value.strip().strip('"').strip("'")
    values: dict[str, str] = {}
    for out_key, candidates in SOURCES.items():
        found = next((raw[c] for c in candidates if raw.get(c)), DEFAULTS.get(out_key))
        if found:
            values[out_key] = found
    if "apiKey" not in values:
        raise SystemExit("FIREBASE_API_KEY ausente no .env: o app vai dizer que nao esta configurado")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join(f"{k}={v}\n" for k, v in sorted(values.items())))
    # Só os NOMES das chaves: o valor nunca vai para a tela.
    print(f"Gerado {OUT.relative_to(REPO)} com {len(values)} chaves: {', '.join(sorted(values))}.")


if __name__ == "__main__":
    main()
