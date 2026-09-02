#!/bin/bash
# O portão local completo, na mesma ordem do iOS: auditoria de i18n primeiro
# (bloqueante), depois testes do domínio puro, depois o build de verdade.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 scripts/i18n-audit.py
./gradlew --console=plain :kit:test :app:assembleDebug "$@"

echo "CI gate green ✔"
