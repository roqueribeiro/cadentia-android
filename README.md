# Cadentia (Android)

App de música **100% nativo Android** (Kotlin + Jetpack Compose + Oboe) para
músicos — a réplica do [cadentia-ios] com a mesma régua: latência como
requisito, palco escuro premium, i18n em 10 idiomas com auditoria bloqueante e
domínio puro testável.

O plano de execução vive no projeto (Goal: Cadentia Android). O escopo desta
réplica é o `main` do iOS (1.14.0): Afinador · Metrônomo · Bateria · Separar ·
Mais (Piano, Gravador, Tablaturas, Frequência, Sobre).

## Arquitetura

- **`:kit`** — domínio puro Kotlin/JVM, espelho do `CadentiaKit` (DSP, teoria
  musical, tablatura, contratos de rede). Testa sem emulador; as fixtures
  binárias são as mesmas do iOS, o que garante a interop `.rostab` e a
  paridade de DSP.
- **`app/ui/`** — o design system (`CzTokens`, `PremiumBackground`, `CzCard`,
  `pageTransition`): estrutura Material 3 sempre, marca por cima, dark-only.
- **`app/features/`** — uma pasta por feature; ViewModel + engine fino. Engine
  **não sintetiza, só agenda**: pede PCM ao `:kit` e agenda no motor.
- **`app/src/main/cpp/`** — o motor de saída: um stream Oboe de baixa latência
  com mixer próprio de 24 vozes; o relógio compartilhado é o contador de
  frames do stream (o papel do hostTime no iOS).

## Comandos

```bash
scripts/ci.sh        # o portão: auditoria de i18n → testes do :kit → build
scripts/test.sh      # só os testes JVM
scripts/build.sh     # só o APK debug
python3 scripts/gen-i18n.py   # regenera res/values*/strings.xml do catálogo
```

## i18n

`i18n/Localizable.xcstrings` (cópia do catálogo do iOS) é a fonte; o gerador
escreve `values*/strings.xml` para os 10 idiomas (pt-BR canônico no default,
`ar` RTL) e **recusa** remover chave sem `--allow-removals`. A auditoria é o
primeiro passo bloqueante do CI: toda `R.string` usada existe nos 10, e
composable não exibe literal sem o marcador `i18n-verbatim`.

[cadentia-ios]: https://github.com/roqueribeiro/cadentia-ios
