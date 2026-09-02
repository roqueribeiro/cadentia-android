# cadentia-android

Réplica Android nativa do `cadentia-ios` (Kotlin + Compose + Oboe/C++).
Produto próprio da família RoqueOS, no mesmo harness do `myabba-android`.

- O plano e as decisões vivem no **Goal: Cadentia Android** (doc do projeto).
  O que está decidido lá não se reabre aqui.
- A fonte da paridade é o iOS `main` 1.14.0: mesma chave de i18n, mesmo
  `.rostab`, mesmos catálogos gerados, mesmos casos de teste no `:kit`.
- Latência é requisito: nada entra no callback do `AudioEngine` que aloque,
  trave lock ou faça I/O. Mudança de áudio só está pronta com escuta no
  aparelho, nunca só com teste verde.
- Toda string nova entra no catálogo (`i18n/Localizable.xcstrings`) nos 10
  idiomas, ou não entra; `scripts/ci.sh` reprova literal em composable.
- O gate é `scripts/ci.sh` e a evidência vai colada no chat, sem resumo.
- Nada do trabalho do Cordas entra aqui antes da autorização escrita do
  Phelipi (a trava vale igual para repo novo).
