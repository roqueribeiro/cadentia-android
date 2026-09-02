# Changelog

## [Não lançado]

### Fase 2 — Bateria e Piano (2026-09-02)

- `:kit`: StereoBuffer, AudioDSP completo (banco modal, biquads RBJ com 8
  tipos, compressor, envelopes, saturação), DrumKitHD (16 pads × 3 kits nas
  razões de Bessel, round robin determinístico, velocity mudando timbre,
  imagem estéreo), DrumPatterns (25 grooves), InstrumentSynth (13 vozes
  sintetizadas: piano com parciais esticados, Rhodes FM, Hammond com Leslie,
  mallets, arco com formantes, naipe, metais, sax, flauta, lead SVF),
  StringVoices (8 modelos Karplus com corpo e afinação compensada) e
  ChordLibrary com 77 acordes gerados do iOS. 93 testes JVM, incluindo pitch
  de toda voz validado pelo YIN a ±12 cents.
- Bateria: pads 3×3 edge-lit no chassi de hardware, disparo no toque para
  baixo, modo edição com prévia de som, sequencer de 16 passos com playhead,
  25 grooves por categoria, kits, BPM, volume e sala de reverb.
- Piano: teclado vertical multitouch com glissando (graves embaixo, pretas
  à esquerda), 13 vozes, oitavas C2–C6, pedal de sustain, modo Acordes (77
  formas com diagrama de violão, bloco e arpejo) e modo Escalas (12 tônicas
  × 12 tipos, braço de 12 casas, tocar a escala).
- I18nMap.kt gerado do catálogo (chave do web → R.string) com verificação
  no gate.

### Fase 1 — núcleo de áudio, Afinador e Metrônomo (2026-09-02)

- `:kit`: port 1:1 com testes do YINPitchDetector, TunerSession,
  MetronomeClick, ToneSynth, BPMDetector, PracticeLoop, LevelMeter,
  SpectrumBands e AppSettings/SettingsCodec (decode tolerante); identidade
  dos catálogos (InstrumentVoice, ScaleType, DrumSynth, ChordLibrary).
  58 testes JVM verdes.
- Afinador: AudioRecord → YIN fora da thread de áudio, EMA 0,3 + hold 2,5 s,
  permissão sem tela de CTA, ring gauge de 150° (zonas, ticks, ♭/♯, ponteiro
  com glow, nota dentro), gráfico rolante de 10 s, pills, haptic ao afinar,
  8 presets e A4 415–466 persistidos.
- Metrônomo: PolyphonicSampler (cache 44 MB LRU sobre o motor C++) +
  MetronomeEngine com lookahead 25 ms/120 ms e rebase pós-stall; dial de
  batidas, tap tempo, compasso, subdivisões, 4 sons, volume, polirritmia,
  detector de BPM pelo microfone e practice timer com alarme.
- Build type `qa`: APK minificado (R8) assinado com a chave de debug, ~10 MB,
  para distribuição de teste fora da Play.
- Pendente de aparelho: escuta dos cliques e do pipeline do afinador; QA
  visual com prints. Nenhuma latência declarada sem medição.

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
