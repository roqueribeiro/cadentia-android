# cadentia-android

Réplica Android nativa do `cadentia-ios` (Kotlin + Compose + Oboe/C++).
Produto próprio da família RoqueOS, no mesmo harness do `myabba-android`.

- O plano e as decisões vivem no **Goal: Cadentia Android** (doc do projeto).
  O que está decidido lá não se reabre aqui.
- A fonte da paridade é o iOS `feat/cordas` 1.16.0 (46): mesma chave de
  i18n, mesmo `.rostab`, mesmos catálogos gerados, mesmos casos de teste no
  `:kit`, mesmos identificadores de UI (`testTag` = `accessibilityIdentifier`).
- Latência é requisito: nada entra no callback do `AudioEngine` que aloque,
  trave lock ou faça I/O. Mudança de áudio só está pronta com escuta no
  aparelho, nunca só com teste verde.
- Toda string nova entra no catálogo (`i18n/Localizable.xcstrings`) nos 10
  idiomas, ou não entra; `scripts/ci.sh` reprova literal em composable.
- O gate é `scripts/ci.sh` e a evidência vai colada no chat, sem resumo.
- O Cordas veio do `github.com/phelipiii/cordas` com o Phelipi de acordo (foi
  ele quem pediu); o crédito e a licença ficam no Sobre.

## Testes instrumentados (as 29 UITests do iOS, aqui 31)

`app/src/androidTest/.../CadentiaUITests.kt`, em uiautomator (a regra do
Compose não serve: telas com anel, LED ou onda nunca ficam "idle"). Rodam
contra o **debug** (o runner não sobrevive ao R8 da `qa`), no emulador:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.levelhard.cadentia.CadentiaUITests \
  com.levelhard.cadentia.debug.test/androidx.test.runner.AndroidJUnitRunner
# um só: -e class com.levelhard.cadentia.CadentiaUITests#nomeDoTeste
```

Falhou? O print e a árvore de acessibilidade do momento estão em
`/sdcard/Download/cadentia-ui/<teste>.png|.xml` (`adb pull`). Os ganchos são
os mesmos `launch args` do iOS como extras (`qa-tab`, `qa-reset`,
`qa-stems-demo`, …); `qa-reset` apaga configurações, memória de mesa e
repertórios, não as Recentes (igual ao iOS).
