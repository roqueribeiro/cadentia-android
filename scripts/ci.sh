#!/bin/bash
# O portão local completo, na mesma ordem do iOS: auditoria de i18n primeiro
# (bloqueante), depois testes do domínio puro, depois o build de verdade.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 scripts/i18n-audit.py
python3 scripts/compose-revision-audit.py
# :app:testDebugUnitTest cobre o que a auditoria estática não vê: chave de
# web produzida em tempo de execução pelos catálogos do :kit (I18nMapCatalogTest).
./gradlew --console=plain :kit:test :app:testDebugUnitTest :app:assembleDebug "$@"

echo "CI gate green ✔"
