# Changelog

## [Não lançado]

### Fase 6 — Base 1.16 (2026-09-02)

- i18n (e8ae9c2): catálogo da 1.16 — 631 chaves × 10 idiomas gerados do
  Localizable.xcstrings da feat/cordas por scripts/gen-i18n.py, com
  i18n/android-overrides.json para as substituições de plataforma. O
  I18nMapCatalogTest resolve toda chave produzida em runtime (escalas,
  vozes, presets, cliques, kits, pads, levadas, backing tracks).
- Afinador (695729c): catálogo de 49 afinações (InstrumentPreset gerado do
  Swift por scripts/gen-instruments.py), folha de busca com recentes e
  seções, fileira de cordas com a alvo acesa.
- Instrumentos (c8104d5): a Bateria cede a aba e vira card do hub; Piano
  sai de Mais; Acordes/Escalas saem do Piano para a seção Estudo. Os
  apelidos de QA (piano, drums, chords, scales) caem no destino novo. Tocar
  de novo na aba atual volta à raiz dela. Cordas e Baixo só na fase 8: card
  que leva a "em obra" é pior que card nenhum.
- Mais (50cc704): ordem da 1.16 (Gravador, Tablaturas, Frequência, Som dos
  instrumentos, Sobre). A folha de Som mostra o estado honesto deste build:
  nenhum banco instalado, tudo em síntese. Bancos na fase 7.
- Áudio (c9da322): aquecimento do Piano e da Bateria fora da thread
  principal. QA no emulador media 243 frames pulados (~4 s de tela
  congelada) ao abrir o Piano. Render fora do lock do cache; start/stop do
  motor dentro do mesmo lock (sem use-after-free no C++). Números das
  casas do braço da escala abaixo da última corda.
- QA no emulador (Medium_Phone, API 36, v4c): hub, os quatro destinos, os
  quatro apelidos de qa-tab, voltar à raiz pela aba, Mais e a folha de Som;
  6 ciclos abrir Piano → tocar → voltar sem crash; Piano abre em <500 ms.
- Pendente de aparelho: tudo que as fases anteriores listavam; o mic do
  emulador segue mudo (permissão do macOS ao qemu).

### Fase 5 — Polimento (2026-09-02)

- Sobre (0c8b1b1): tela real portada do AboutView.swift — cabeçalho com
  versão e código de build, as quatro seções (o que é, separação local,
  privacidade, RoqueOS), quem fez com os dois LinkedIn como alvo de linha
  inteira, links de site/privacidade/termos/comunidade e folha de licenças
  com o MIT do Demucs verbatim (obrigação da licença). O FeatureHero saiu
  do repo: era o placeholder de obra e perdeu o último chamador.
- Afinador (4510f41): análise de sessão — tee das amostras cruas no próprio
  loop do AudioRecord (nenhum segundo microfone), linha do tempo de pitch a
  15 Hz presa ao sinal vivo, teto de 60 s, WAV íntegro no stop via WavIO, e
  folha de resumo com as métricas do TunerSession do `:kit` (nota dominante,
  tempo afinado, desvio médio), gráfico da sessão e replay por MediaPlayer
  (latência não é requisito num replay; o PolyphonicSampler fica de fora).
- Acessibilidade (caf1f37): rótulos TalkBack onde o catálogo já tinha a
  chave (play/pause do Separar, esquecer recente, voltar na biblioteca),
  Reduce Motion na troca de destino do Mais (a única animação que faltava
  gatear), e alvo mínimo de 48 dp nos controles pequenos via
  minimumInteractiveComponentSize sem mudar o visual. Onde falta chave no
  catálogo (aumentar/diminuir, desfazer/refazer, ±10 s, compartilhar/
  editar) ficou pendência comentada — chave nova só nos 10 idiomas.
- Gate da fase: i18n-audit 465 × 10; 236 testes JVM (0 falhas, reexecutados
  do zero); assembleDebug/Qa OK.
- Pendente de aparelho: escuta da gravação e do replay da análise; TalkBack
  e Remover animações lidos de verdade; tudo que as fases 1–4 já listavam.

### Fase 4 — Separar (stems) e Biblioteca RoqueOS (2026-09-02)

- `:kit` (5ddf535): StemMix (solo vence mute) + StemMixSnapshot/Memory
  (ajuste por música, neutro apaga, teto 200), RecentSongs (identidade
  FNV-1a pela origem, teto 30) + SongSearch sem acento, Setlists + SetQueue
  (embaralhado é permutação com Random injetável) e os seis contratos do
  roqueos-server nascidos de bugs reais (NetworkStorage array-na-raiz,
  ServerFilesystem envelope files, Headers com X-Firebase-Token,
  SessionDurability, DownloadIntegrity, ServerSelection). 72 testes novos.
- `:kit` (f7b46b4): RealFFT radix-2 própria + DemucsSpectrogram exato do
  HTDemucs (nfft 4096/hop 1024/2048 bins, dois paddings empilhados) com
  paridade < 1e-5 contra o PyTorch nas fixtures verbatim, na primeira
  execução, tolerância intacta.
- `:kit` (1891982): StemPipeline (janelas 7,8 s, overlap 0,25, cross-fade
  sin², streaming com memória de UMA janela, StemBackend plugável),
  StemCachePolicy e StemResampler (sinc 32 taps, tom a ±3 cents no YIN).
  Formato dos tensores do modelo anotado para o backend futuro.
- Separar (082e31f): StemPlayerEngine num AudioTrack único (mix nosso =
  sincronia por construção; setPlaybackParams = velocidade 0,5–1,5x sem
  mudar afinação e tom ±12 semitons sem mudar tempo), loop A/B pelo caminho
  do seek, espectro de 48 bandas e medidores; StemsScreen completa
  (biblioteca com busca e repertórios/fila, onda interpolada no ritmo da
  tela, ThinSlider com o loop pintado nele, mixer em folha com chave e
  velocidade em grade de 5%, memória de mix por música); normalizador
  MediaExtractor/MediaCodec (decodifica pelo conteúdo) + resample 44,1 k.
- Biblioteca RoqueOS (f381b8c, 1acb7b2): pareamento por código curto + QR
  gerado no aparelho (zxing-core; URL de pareamento nunca sai para serviço
  externo), claim idempotente com uid do claim, refresh token cifrado por
  chave presa no Android Keystore, renovação coalescida que só derruba a
  sessão quando o Firebase confirma revogação; as quatro fontes (Firebase,
  discos mapeados com credencial POR servidor, /shared preso ao próprio
  /shared, Google Drive por token de vida curta), download com dl=1 +
  DownloadIntegrity e credencial só em cabeçalho; navegador integrado à
  tela Separar, item remoto vira Recente e rebaixa por refetch.
- FATOS documentados: o modelo de separação (103 MB no iOS) está ausente
  até do clone — o Separar mostra o estado honesto de modelo indisponível e
  o backend ONNX entra quando houver modelo exportado para validar contra
  as fixtures; a config RoqueOS (chave de cliente Firebase) é gitignored e
  gerada por scripts/gen-roqueos-config.py — ausente, o pareamento diz
  "não configurado", como no clone do iOS.
- Gate da fase: i18n-audit 465 × 10; 236 testes JVM; assembleDebug/Qa OK.
- Pendente de aparelho: player de stems sem escuta humana; pareamento sem
  teste contra servidor real (sem config no container).

### Fase 3 — Tablaturas, Gravador e Frequência (2026-09-02)

- `:kit` (786650b): o modelo .rostab inteiro — Tablature/RostabParser com
  round-trip da fixture do web verbatim, TablatureEdit (toda mutação do
  editor), TabRowDisplay, playbackPlan v5.1 (blocos ×N e compasso ∞ com
  loop modular), ChordLibrary e BackingTrackCatalog com 48 bases GERADAS do
  Swift + BackingTrackBuild (levada por gênero, baixo por graus na corda
  mais grave PELO TOM, teste nota-a-nota contra o acorde do compasso).
- Tablaturas (7024cc9): TabPlayerEngine no relógio de frames (plano por
  trilha, count-in, loop, BPM override, mute/solo/volume ao vivo, masterFx,
  articulações como velocity, round robin, duração quantizada no cache),
  grade em Canvas (aguda em cima, playhead com auto-rolagem a 35%, LTR
  travado), editor completo com undo 24 (casas 0–24, SATB, figuras, palm
  mute, repetições ×N, blocos), mixer, catálogo de 48 bases por gênero,
  escolha de acorde, demo embutida + abrir .rostab (SAF) + compartilhar
  (FileProvider).
- Gravador (243344f, 977506d): RecorderProject/History no `:kit` (clipe =
  janela sobre arquivo; split sem perda; migração do formato legado; 20
  testes 1:1) + RecorderMix (soma offline pura com envelope/pan/volume,
  enhance 70 Hz + compressor; 6 testes) + WavIO. No app: RecorderEngine
  (chunks de 0,5 s em lookahead no relógio de frames, captura
  VOICE_COMMUNICATION com AEC no modo estúdio / UNPROCESSED fora, take só
  grava depois do count-in), timeline com zoom/pan/scrub/drag/aparo/split/
  duplicar, R/M/S, undo/redo, sheets de trilha e clipe, mixdown M4A
  (MediaCodec AAC 192k) compartilhável. Sem chip de Isolamento de Voz: o
  painel é do sistema da Apple; o modo estúdio AEC é o equivalente Android.
- Frequência (6d7be6e): gerador de tom contínuo por chunks com fase
  contínua e agendamento em frames inteiros (emenda sem estalo; knobs
  respondem em ~80 ms com fade), binaural por canal, reverb/delay do bus,
  osciloscópio 1:1, slider log 20 Hz–20 kHz, presets, A4·440, 4 ondas.
- Gate da fase: i18n-audit 465 chaves × 10 idiomas; 148 testes JVM verdes;
  assembleDebug + assembleQa OK.
- Pendente de aparelho: TODO o áudio novo (tablaturas, gravador, gerador)
  sem escuta humana; overdub sem medição de alinhamento em hardware real.

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
