#!/usr/bin/env python3
"""Gera app/src/main/assets/roqueos-config.properties do .env do roqueos-front.

Espelho do scripts/gen-roqueos-config.mjs do cadentia-ios: a chave do Firebase
é a CHAVE DE CLIENTE (a mesma do bundle JS do site) — identifica o projeto,
não autoriza nada. Mesmo assim fica fora do git (o arquivo gerado é ignorado).

    python3 scripts/gen-roqueos-config.py ../roqueos-front/.env
"""

import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "app" / "src" / "main" / "assets" / "roqueos-config.properties"

MAPPING = {
    "VITE_FIREBASE_PROJECT_ID": "projectID",
    "VITE_FIREBASE_API_KEY": "apiKey",
    "VITE_FUNCTIONS_REGION": "functionsRegion",
    "VITE_WEB_BASE_URL": "webBaseURL",
}


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    env = Path(sys.argv[1])
    values: dict[str, str] = {}
    for line in env.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() in MAPPING:
            values[MAPPING[key.strip()]] = value.strip().strip('"')
    if "apiKey" not in values:
        raise SystemExit("a chave de cliente do Firebase nao esta no .env")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join(f"{k}={v}\n" for k, v in sorted(values.items())))
    print(f"Gerado {OUT.relative_to(REPO)} com {len(values)} chaves.")


if __name__ == "__main__":
    main()
