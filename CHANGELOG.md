# Changelog

## [Não lançado]

### Fase 15 — Testes instrumentados (2026-09-05)

- `app/src/androidTest/CadentiaUITests.kt`: 31 testes em uiautomator
  espelhando as 29 UITests do iOS (afinador, afinações, metrônomo, hub,
  bateria, piano, bancos, escalas, Cordas com batida de borda, stems demo,
  loop A/B, velocidade lembrada, repertórios em ordem, Estúdio, tablatura,
  gravador, Sobre, Som, troca de aba, HOME e volta). 31/31 verdes no
  emulador (Medium_Phone, Android 17). Falha grava print e árvore de
  acessibilidade em `/sdcard/Download/cadentia-ui/`.
- `testTag` com os nomes do iOS nas telas (`tuner.gauge`, `metronome.toggle`,
  `drums.pads`, `piano.keyboard`, `stems.*`, `setlist.*`, `about.licenses`…)
  e `testTagsAsResourceId` na raiz e em cada folha modal
  (`Modifier.exposeTestTags()`); botão fechar na folha Som (`sound.close`).
- Defeitos que os testes acharam e foram corrigidos: o "+" de velocidade e
  tom do mixer mudava o áudio sem atualizar o rótulo (`persistMix` não
  recompunha); voltar do sistema com o mixer aberto largava a pessoa no
  launcher (agora fecha o mixer, e no player volta à biblioteca); o topo do
  mixer escondido aparecia por trás da barra de abas translúcida (o
  `modifier` do `BottomSheetScaffold` só clipa o corpo); `qa-reset` não
  apagava memória de mesa nem repertórios (o iOS apaga).
- `BackHandler` no Cordas engole o voltar por gesto de borda que a batida
  provoca (a exclusão de gesto é limitada a 200 dp pelo sistema).
- androidx.test 1.7.0 / espresso 3.7.0 (3.6.1 quebra no Android 16+:
  `InputManager.getInstance`). Testes rodam contra o `debug`: o runner não
  sobrevive ao R8 da `qa`.

### Fases 10–14 (2026-09-04, `7e23ba9` … `b4d1af3`)

- Fase 10: 7 packs de sample no APK, fotos dos instrumentos, hub com barra,
  Estudo com nome da nota, choke do chimbal, Sobre com Cordas/Phelipi.
- Fase 11: separação real com ONNX Runtime (htdemucs exportado em blocos,
  sem ConstantFolding: pico 0,8 GB), faixas em AAC, `PeakLimiter`, `StemsQA`.
- Fase 12: `PlaybackSession` (foco de áudio) + `PlaybackService` (FGS de
  reprodução com "Parar"); Oboe reabre o stream em `onErrorAfterClose`.
- Fase 14: fader/pan imediato no Gravador (comando Mix), pads de acorde
  acessíveis, telemetria do Cordas em arquivo, troca de faixa animada e
  mixer em `BottomSheetScaffold` com detentes.

### Fase 9 — Separar 1.16 (2026-09-02)

- Kit: `StemCachePolicy.evictions` — a limpeza só age quando o aparelho
  fica sem espaço (reserva de 2 GB), as cinco mais novas e as de repertório
  nunca saem, e se apagar tudo ainda não alcança a reserva, não apaga nada
  (8 testes portados do 1.16). `RecentSongs.LIMIT` 30 → 200: a lista de
  recentes deixou de ser a prova de vida das faixas separadas.
- `StemCache` (port do 1.16): faixas em `filesDir/stems` (fora do backup:
  `backup_rules.xml` e `data_extraction_rules.xml`), migração automática da
  pasta velha em `cacheDir`, escrita em `<id>.parcial` e publicação por
  `rename` (ou a pasta está inteira, ou não está), varredura de entulho ao
  entrar na tela, `usage()` para a tela dizer quanto ocupa.
- `SeparationService`: serviço de primeiro plano (`mediaProcessing` no
  Android 15+, `dataSync` antes) com notificação de progresso e botão de
  cancelar — o papel do `SeparationJob`/Ilha Dinâmica. Wake lock parcial com
  teto de 6 h. **Achado no emulador (API 37):** `ServiceCompat.startForeground`
  do core 1.16 mascara o tipo com os tipos do Android 14, e `mediaProcessing`
  virava "type none" — "Starting FGS with type none ... has been prohibited".
  A chamada passou a ser a da plataforma; medido depois: `tipo 8192` aceito.
  Atualizar e parar falam com a instância viva (nada de `startService` do
  fundo), e um `stop` que chega antes de o serviço nascer o mata ao nascer.
- `StemsModel` (port do `StemsModel.swift`): a tela saiu do estado inline
  para o modelo — `open`/`openMany` (leva em série, a primeira que dá certo
  vai para o player, falhas listadas sem derrubar o resto), `reopen` com
  rebaixar da origem remota, `materialize` (normaliza fora da thread
  principal, valida a entrada, publica por rename, `trim` só depois),
  `cancelBatch` em todo "quero outra coisa agora", `clearStorage`,
  `sweepStorage`, fila do repertório e loop A/B como antes. A separação em si
  continua parando em `modelMissing` — o mesmo lugar do iOS sem o
  `Separator.mlmodelc`.
- Tela: `SeparatingView` (anel com porcentagem ou ícone respirando, as
  quatro faixas em ícones, fila da leva com feito/atual/próximas e rolagem
  para a atual, andamento da leva, estimativa honesta do que falta — só
  acima de 6% —, e o convite para sair do app). Faixa da leva no topo em
  qualquer estado, opaca, com X de 44 dp que cancela de verdade. Biblioteca
  na ordem do 1.16: repertórios, recentes (seis e "Ver todas (N)", "Pronta
  para tocar"/"Vai separar de novo" em cada linha, espaço usado tocável com
  diálogo de apagar), "Neste aparelho" (seletor do sistema com seleção
  múltipla, permissão de notificação pedida inline no Android 13+) e RoqueOS.
- Navegador RoqueOS: Selecionar/Concluir, marca por música atravessando
  pastas, "Tudo desta pasta", "Importar N" no topo, downloads em série com
  "Baixando i de N" e falhas listadas; as cópias temporárias somem depois de
  normalizar.
- i18n: `thisDeviceHint` sem "iCloud Drive" nos 10 idiomas (override do
  Android); o gerador converte `%N$lld`/`%N$@` para `%N$d`/`%N$s` e a
  auditoria bloqueia especificador do iOS no `strings.xml`.
- QA no emulador (v7 → v7b): biblioteca vazia e com 12 recentes, separação
  em 42%/0%/leva de 12, "Ver todas"/"Ver menos", player a partir do cache
  (toca), espaço usado → diálogo → apagado (pasta sumiu), importação de 3 e
  de 2 arquivos pelo seletor do sistema (leva, faixa, falha com o motivo),
  importação de 1, permissão de notificação inline (revogada → pedida →
  concedida → leva segue), X da faixa, "Tentar outra". Buffer de crash
  limpo. Não medido: a notificação na tela (o fluxo termina em ~200 ms sem
  o modelo) e o cancelar pela notificação.

### Fase 8 — Cordas (2026-09-02)

- Kit (0d1f06d): os 16 arquivos do Cordas portados 1:1 do 1.16 —
  CordaString (Karplus-Strong de duas polarizações, SplitMix64 idêntica
  ao Swift), CordaBody, CordaInstrument (violão, guitarra, viola caipira,
  baixo), CordaChords, FretboardLayout, NailCapture, FixedStringsStrummer,
  HandFeatures/HandSmoother/HandChordMapping/TwoHandChords,
  AirGuitarGeometry, CameraFrameMapping. 133 testes, incluindo afinação
  medida pelo YIN e o decaimento em dois estágios. PianoVoicing (+4).
- Motor (1863f90): pan de potência constante e taxa por voz (varispeed com
  interpolação linear — bend, glissando, humanização de ±0,25%), comando
  Rate, barramento elétrico (drive cúbico + gabinete de três biquads)
  antes do reverb, volume mestre, buffers mono no cache, xrunCount e
  bufferSizeInFrames expostos. Medido no host com 24 vozes e callback de
  96 frames: 1,9 µs antes, 4,9 µs depois (8,7 µs com taxa ≠ 1) — 0,4% do
  orçamento de 2 ms; latência estrutural intocada.
- App (1863f90, 8651229): CordaEngine sobre o PolyphonicSampler (dois
  baldes de dinâmica, cache de 48 MB, aquecimento fora da thread principal
  com as cordas soltas primeiro, palm mute como envelope, bend por
  setVoiceRate, tchac); CordasModel; braço com toque cru (touchMajor,
  amostras históricas, varredura até o UP); CordasScreen com barra,
  painel (som, capo, casas, espalhamento, régua de 54 mm e medidas
  honestas), treinador por modo e HandChordSheet. Modo câmera com CameraX
  + MediaPipe Hand Landmarker 1.0.0 (modelo nos assets, Apache-2.0 nas
  licenças do Sobre); o registro do backend CCT do datatransport é
  removido no manifesto e o runtime descarta a estatística de uso do
  MediaPipe — nada sai do aparelho. ReplayHands para QA sem câmera.
- Hub: cards Cordas e Baixo (abre o Cordas no baixo), na ordem do iOS.
  Estudo com seletor de instrumento (piano × violão × guitarra × viola ×
  baixo nas escalas), teclado de estudo, diagrama de acorde por
  instrumento e braço de escalas com a afinação do instrumento.
- QA no emulador (v6 → v6c): braço, batida, trilho, hands-free, pestana,
  acordes, baixo, viola, painel, treinador, câmera real (29–30 fps) e
  replay, hand chords, Estudo, selftest nos quatro instrumentos com
  xruns=0. Achados corrigidos: batida rápida deixava as últimas cordas
  mudas, prévia da câmera vazando por cima da barra, dica sobre a faixa
  de acordes, número da casa sob a bolinha da tônica.
- Em aberto: escuta no aparelho (síntese × sample, drive), latência de
  toque real, câmera com mãos de verdade e a chirality (o MediaPipe rotula
  assumindo imagem espelhada; a etiqueta é trocada e os botões Espelho e
  Mão são a saída).

### Fase 7 — Bancos de sample (2026-09-02)

- Kit (9d56468): SamplePack/SampleFamily/SampleSelection e SampleBank
  portados do 1.16 — o MESMO manifesto JSON que o iOS lê, cache LRU por
  bytes (96 MB), Hermite de 4 pontos, loop, rampa de release, pan,
  percussão sem transposição, aquecimento com orçamento, purge e a chave
  por família (generation). InstrumentSynth.render e DrumSynth.renderStereo
  consultam o banco antes da síntese; sem pack ou com a família desligada,
  nada muda. AppSettings.sound.sampled (tudo ligado por padrão). 29 testes,
  incluindo um pack de verdade em WAV medido pelo YIN e o manifesto real do
  pack de bateria.
- App (9d56468, eaa4f65): decodificador FLAC por MediaExtractor/MediaCodec,
  instalação em `filesDir/samples` (ou externalFilesDir), purge em
  onTrimMemory, chave síntese × sample seguindo as configurações.
  DrumVoicing (chave por arquivo, acento no ganho da voz), soundGeneration
  nas chaves do Piano, Estudo, tablatura e prévia dos pads. Folha Som dos
  instrumentos completa (interruptor por família, origem e licença de cada
  banco, créditos — também nas licenças do Sobre), cartão de Mais com
  "N/M · gravações reais". Aquecimento da bateria como no 1.16 (pads da
  levada + núcleo, fora da thread principal, também no Start).
- Pipeline: scripts/fetch-samples.mjs e samples.config.mjs do iOS, tal
  qual, com a saída em `samples/` (fora do git e do APK). Rodado: 7 packs
  CC0 (FreePats ×5, Karoryfer Growlybass, Versilian Virtuosity Drums),
  37 MB de FLAC, LICENSE.txt por pack.
- QA no emulador (v5 → v5d) com os 7 packs: instalação, folha de Som,
  Piano aquecendo em fundo, bateria acústica em sample (21 arquivos em
  fundo, Start sem frame pulado), tablatura demo com violão + baixo +
  bateria em sample, família desligada zerando as decodificações. Achados
  corrigidos: Start pulava 84 frames (aquecimento síncrono), nomes dos
  bancos cortados, tag de log inválida.
- Em aberto: como entregar os 37 MB no aparelho (asset pack, download ou
  build sem bancos) — decisão do Roque; escuta no aparelho.

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
