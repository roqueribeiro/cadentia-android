#!/bin/bash
# Do repo até a página de review — e para ali.
#
#   scripts/ship.sh              tudo: imagens, metadados, binário, ficha
#   scripts/ship.sh --listing    só a ficha (imagens + textos), sem tocar no binário
#   scripts/ship.sh --build      só o binário para o TestFlight
#   scripts/ship.sh --check      só confere se esta máquina consegue fazer o resto
#
# O que ele NUNCA faz: apertar "Enviar para revisão". Esse continua seu, porque
# é a última chance de olhar a página inteira antes de ela virar pública.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
APP="$(basename "$REPO_ROOT")"

bold=$'\e[1m'; red=$'\e[31m'; yel=$'\e[33m'; grn=$'\e[32m'; dim=$'\e[2m'; off=$'\e[0m'
step() { echo; echo "${bold}▸ $*${off}"; }
die()  { echo "${red}✗ $*${off}" >&2; exit 1; }
warn() { echo "${yel}! $*${off}"; }
ok()   { echo "${grn}✓ $*${off}"; }

MODE=all
case "${1:-}" in
  --listing) MODE=listing ;;
  --build)   MODE=build ;;
  --check)   MODE=check ;;
  --help|-h) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  "")        ;;
  *)         die "opção desconhecida: $1" ;;
esac

# ── o que esta máquina precisa ter ────────────────────────────────────────────
step "conferindo a máquina"
missing=0
need() { command -v "$1" >/dev/null 2>&1 && ok "$1" || { echo "${red}✗ $1 — $2${off}"; missing=1; }; }

need node "instale o Node 20+"
need fastlane "brew install fastlane"
[ -f store.config.json ] || die "sem store.config.json — rode o storekit init"

if [ -f project.yml ]; then                    # app iOS
  PLATFORM=ios
  need xcodebuild "instale o Xcode"
  need xcodegen "brew install xcodegen"
  [ -n "${RELEASE_TEAM_ID:-}" ] || { warn "RELEASE_TEAM_ID não definido — export RELEASE_TEAM_ID=K8A376ZGPX"; missing=1; }
  if [ -n "${ASC_KEY_PATH:-}" ] && [ -f "${ASC_KEY_PATH}" ]; then ok "chave da App Store Connect"
  else
    echo "${red}✗ chave da App Store Connect${off}"
    echo "${dim}    App Store Connect → Usuários e Acesso → Integrações → Chaves da API → +${off}"
    echo "${dim}    papel App Manager. O .p8 só pode ser baixado uma vez.${off}"
    echo "${dim}    export ASC_KEY_ID=… ASC_ISSUER_ID=… ASC_KEY_PATH=~/.appstoreconnect/private_keys/AuthKey_….p8${off}"
    missing=1
  fi
else                                            # app Android
  PLATFORM=android
  [ -f keystore.properties ] && ok "keystore de upload" || {
    echo "${red}✗ keystore.properties — copie de keystore.properties.template e gere a chave${off}"
    echo "${dim}    keytool -genkey -v -keystore ~/keys/${APP%-android}-upload.jks -alias ${APP%-android} -keyalg RSA -keysize 2048 -validity 10000${off}"
    missing=1
  }
  if [ -n "${SUPPLY_JSON_KEY:-}" ] && [ -f "${SUPPLY_JSON_KEY}" ]; then ok "conta de serviço da Play"
  else
    echo "${red}✗ conta de serviço da Play${off}"
    echo "${dim}    Play Console → Configuração → Acesso à API → criar no Google Cloud → conceder acesso → baixar JSON${off}"
    echo "${dim}    export SUPPLY_JSON_KEY=~/.playconsole/play-service-account.json${off}"
    missing=1
  fi
fi

# Capturas cruas precisam existir: sem elas o build molda o nada.
shots=$(find store/sources -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
[ "$shots" -gt 0 ] && ok "$shots capturas em store/sources" || {
  echo "${red}✗ store/sources vazio${off}"
  [ "$PLATFORM" = ios ] && echo "${dim}    rode scripts/store-capture.sh${off}" \
                        || echo "${dim}    rode scripts/store-capture-android.sh${off}"
  missing=1
}

[ "$MODE" = check ] && { echo; [ "$missing" = 0 ] && ok "pronto para rodar sem --check" || warn "resolva os ✗ acima"; exit "$missing"; }
[ "$missing" = 0 ] || die "faltam pré-requisitos — rode com --check para a lista"

# ── 1. imagens + metadados ────────────────────────────────────────────────────
if [ "$MODE" != build ]; then
  step "montando a ficha"
  scripts/store.sh build || die "o preflight barrou — corrija e rode de novo"
fi

# ── 2. binário ────────────────────────────────────────────────────────────────
if [ "$MODE" != listing ]; then
  step "binário"
  if [ "$PLATFORM" = ios ]; then
    scripts/release.sh || die "archive/upload falhou"
    ok "build no ar — o TestFlight ainda vai processar por alguns minutos"
  else
    ./gradlew bundleRelease || die "bundleRelease falhou"
    AAB=$(find app/build/outputs/bundle/release -name '*.aab' -print -quit)
    [ -n "$AAB" ] || die "não achei o .aab"
    ok "$AAB"
  fi
fi

# ── 3. ficha nas lojas ────────────────────────────────────────────────────────
if [ "$MODE" != build ]; then
  step "subindo a ficha"
  if [ "$PLATFORM" = ios ]; then
    fastlane ios listing || die "deliver falhou"
  else
    fastlane android listing || die "supply falhou"
    [ "$MODE" = all ] && { fastlane android internal || warn "a trilha interna falhou; a ficha subiu"; }
  fi
fi

# ── o que sobra, que é humano de propósito ────────────────────────────────────
echo
echo "${bold}Falta você, no console:${off}"
if [ "$PLATFORM" = ios ]; then
  cat <<'TXT'
  1. Privacidade do app — as etiquetas de dados
  2. Preço e disponibilidade
  3. Direitos de exportação (isenção de criptografia)
  4. Selecionar o build que veio do TestFlight
  5. Enviar para revisão
TXT
else
  cat <<'TXT'
  1. Questionário de classificação etária
  2. Política de privacidade e formulário de segurança dos dados
  3. Público-alvo e declaração de anúncios
  4. Promover a trilha interna para produção
TXT
fi
echo "${dim}Nada disso está na API das lojas — não é esquecimento do script.${off}"
