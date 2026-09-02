# Changelog

## [Não lançado]

### Fase 0 — scaffold (2026-09-02)

- Projeto Gradle: AGP 8.13, Kotlin 2.2.20, Gradle 9.5.1, compileSdk 36,
  minSdk 29, NDK 27 fixado, abi arm64-v8a + x86_64.
- `:kit` (domínio puro JVM) com o primeiro port: `MusicNotes` +
  `InstrumentPreset`, 15 casos de teste 1:1 com o `MusicNotesTests` do iOS.
- Design system: `CzTokens`, `PremiumBackground` (glow que respira, Reduce
  Motion vira quadro parado), `CzCard`, `pageTransition`, tema Material 3
  dark-only com dígito tabular.
- Cinco abas navegando (Afinador · Metrônomo · Bateria · Separar · Mais) com
  acento por aba; Mais com os cinco cards e navegação interna; splash do anel
  dourado; ícone adaptativo do anel + diapasão.
- i18n: 465 chaves × 10 idiomas geradas de `i18n/Localizable.xcstrings`;
  auditoria bloqueante no `scripts/ci.sh` (catálogo íntegro, R.string
  existente, literal proibido sem `i18n-verbatim`).
- Motor de áudio C++ (Oboe 1.9.3 por prefab, STL compartilhada): stream
  EXCLUSIVE/LowLatency 48 kHz, mixer de 24 vozes, agendamento por frame,
  filas lock-free no callback, reverb Schroeder + delay de bus, limiter.
  Compila nas duas ABIs; ainda sem escuta em aparelho.
- QA por extras de intent, espelhando os launch args do iOS: `-e qa-tab`,
  `--ez qa-no-splash true`.
