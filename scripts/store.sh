#!/bin/bash
# Thin wrapper. The harness lives in the ecosystem; the listing lives here.
#
#   scripts/store.sh build     molda painéis, escreve metadados, roda o preflight
#   scripts/store.sh check     só confere o que já está gerado
#   scripts/store.sh doctor    esta máquina consegue rodar tudo?
#
# Override the harness location with STOREKIT_HOME if your checkout differs.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

KIT="${STOREKIT_HOME:-}"
if [ -z "$KIT" ]; then
  for candidate in \
    "$REPO_ROOT/../roqueos-repos/roqueos-ecosystem/store-kit" \
    "$REPO_ROOT/../roqueos-ecosystem/store-kit" \
    "$HOME/workspaces/roqueos-repos/roqueos-ecosystem/store-kit"; do
    [ -d "$candidate" ] && KIT="$candidate" && break
  done
fi
[ -n "$KIT" ] && [ -d "$KIT" ] || {
  echo "store-kit não encontrado. Defina STOREKIT_HOME apontando para roqueos-ecosystem/store-kit."; exit 1;
}

[ -d "$KIT/node_modules" ] || (echo "▸ instalando dependências do store-kit"; cd "$KIT" && yarn install --silent)

exec node "$KIT/bin/storekit.mjs" "${1:-build}" --root "$REPO_ROOT" "${@:2}"
