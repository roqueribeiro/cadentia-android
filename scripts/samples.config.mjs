/**
 * O catálogo de packs de sample do Cadentia.
 *
 * Só entra aqui o que tem licença ESCRITA, do titular, permitindo uso
 * comercial e redistribuição embutida. A licença fica arquivada junto do
 * pack (`LICENSE.txt`) — página some, prova fica.
 *
 * `voice` é o id da voz do Cadentia que o pack substitui (InstrumentVoice)
 * ou "drums:<kit>" para a bateria.
 *
 * `prune` é o que impede um pack de 1 GB de virar um app de 1 GB:
 *   velocities  quantas camadas de dinâmica manter (as extremas + espaçadas)
 *   roundRobins quantas variações manter por zona
 *   keyStep     manter só uma zona a cada N semitons (o resto vem por
 *               transposição — é o que todo sampler faz entre as zonas)
 *   maxSeconds  corta a cauda; nota de violão não precisa de 8 s
 */

export const CACHE = 'cadentia-samples-cache'

export const PACKS = [
  {
    id: 'guitar-clean',
    voice: 'guitar-clean',
    name: 'FreePats FSBS Electric Guitar (clean)',
    license: 'CC0-1.0',
    licenseURL: 'https://freepats.zenvoid.org/ElectricGuitar/clean-electric-guitar.html',
    source: 'https://github.com/freepats/electric-guitar-FSBS-clean',
    url: 'https://github.com/freepats/electric-guitar-FSBS-clean/releases/download/2026-08-07/EGuitarFSBS-clean-SFZ+FLAC-20260807.7z',
    // O nome traz o captador e a data; casar o começo mantém o pack preso
    // ao captador certo sem prender a uma data de lançamento.
    sfz: /EGuitarFSBS-clean bridge .*\.sfz$/,
    prune: { velocities: 2, roundRobins: 2, keyStep: 3, maxSeconds: 4.0 },
  },
  {
    id: 'guitar-jazz',
    voice: 'guitar-jazz',
    name: 'FreePats FSBS Electric Guitar (jazz)',
    license: 'CC0-1.0',
    licenseURL: 'https://freepats.zenvoid.org/ElectricGuitar/clean-electric-guitar.html',
    source: 'https://github.com/freepats/electric-guitar-FSBS-jazz',
    url: 'https://github.com/freepats/electric-guitar-FSBS-jazz/releases/download/2026-08-07/EGuitarFSBS-jazz-SFZ+FLAC-20260807.7z',
    sfz: /EGuitarFSBS-jazz bridge .*\.sfz$/,
    prune: { velocities: 2, roundRobins: 2, keyStep: 3, maxSeconds: 4.0 },
  },
  {
    id: 'guitar-nylon',
    voice: 'guitar-nylon',
    name: 'FreePats Spanish Classical Guitar',
    license: 'CC0-1.0',
    licenseURL: 'https://freepats.zenvoid.org/Guitar/acoustic-guitar.html',
    source: 'https://freepats.zenvoid.org/Guitar/acoustic-guitar.html',
    url: 'https://freepats.zenvoid.org/Guitar/SpanishClassicalGuitar/SpanishClassicalGuitar-SFZ+FLAC-20190618.7z',
    sfz: /SpanishClassicalGuitar.*\.sfz$/,
    prune: { velocities: 2, roundRobins: 2, keyStep: 2, maxSeconds: 4.0 },
  },
  {
    id: 'acoustic-piano',
    voice: 'acoustic-piano',
    name: 'FreePats Upright Piano KW',
    license: 'CC0-1.0',
    licenseURL: 'https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html',
    source: 'https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html',
    url: 'https://freepats.zenvoid.org/Piano/UprightPianoKW/UprightPianoKW-SFZ+FLAC-20220221.7z',
    sfz: /UprightPianoKW-\d+\.sfz$/,
    prune: { velocities: 2, roundRobins: 1, keyStep: 3, maxSeconds: 6.0 },
  },
  {
    id: 'organ',
    voice: 'organ',
    name: 'FreePats Drawbar Organ Emulation',
    license: 'CC0-1.0',
    licenseURL: 'https://freepats.zenvoid.org/Organ/electric-organ.html',
    source: 'https://freepats.zenvoid.org/Organ/electric-organ.html',
    url: 'https://freepats.zenvoid.org/Organ/DrawbarOrganEmulation/DrawbarOrganEmulation-SFZ-20190712.tar.xz',
    sfz: /DrawbarOrganEmulation.*\.sfz$/,
    prune: { velocities: 1, roundRobins: 1, keyStep: 3, maxSeconds: 4.0 },
  },
  {
    id: 'bass-fingered',
    voice: 'bass-fingered',
    name: 'Karoryfer Growlybass',
    license: 'CC0-1.0',
    licenseURL: 'https://raw.githubusercontent.com/sfzinstruments/karoryfer.growlybass/master/LICENSE',
    source: 'https://github.com/sfzinstruments/karoryfer.growlybass',
    url: 'https://github.com/sfzinstruments/karoryfer.growlybass/archive/refs/heads/master.zip',
    sfz: /growlybass_clean\.sfz$/,
    // Três camadas e não duas, e sem a mais forte: ver `dropTopVelocity` em
    // `fetch-samples.mjs`. Com duas, o app só tinha pianíssimo e fortíssimo, e
    // tocar normal caía no fortíssimo — que é a camada de tapa.
    prune: {
      velocities: 3, roundRobins: 2, keyStep: 3, maxSeconds: 4.0,
      dropTopVelocity: true,
    },
    // O SFZ declara a oitava errada. `a4_ff_rr1.wav` está mapeado em
    // `pitch_keycenter=69` (lá 440 Hz) e tem fundamental medido de 220,21 Hz,
    // com o parcial de 220 Hz 7,1 dB acima do de 440. O readme confirma: a
    // nota mais grave gravada é C#1 (34,65 Hz), mapeada em 37 (C#2). Sem esta
    // correção, ligar o sample do baixo o joga uma oitava abaixo da síntese.
    keyOffset: -12,
    // As teclas 81–84 do Growlybass são raspadas de palheta, não notas, e a
    // regra "a zona mais aguda nunca some" as mantinha no topo da extensão.
    excludeSample: /scrape/i,
  },
  {
    id: 'drums-acoustic',
    voice: 'drums:acoustic',
    name: 'Versilian Studios Virtuosity Drums',
    license: 'CC0-1.0',
    licenseURL: 'https://raw.githubusercontent.com/sfzinstruments/virtuosity_drums/master/LICENSE',
    source: 'https://github.com/sfzinstruments/virtuosity_drums',
    url: 'https://github.com/sfzinstruments/virtuosity_drums/archive/refs/heads/master.zip',
    // `01-basic-kit`, não `02-full-kit`, e a escolha é deliberada.
    //
    // O programa completo inclui `keymaps/keymap.sfz`, que empilha TODOS os
    // estados de chimbal na tecla 42 e os separa por CC4 (aberto 0–31, 3/4
    // 32–63, meio 64–95, fechado 96–127). O Cadentia não manda CC4, então com
    // ele o chimbal aberto simplesmente deixa de existir — a tecla 46 some do
    // mapa. O `keymap_basic.sfz` mantém 42 fechado e 46 aberto em teclas
    // separadas, que é o que o app precisa. Os dois programas trazem os cinco
    // microfones.
    //
    // Este padrão já foi `/Virtuosity Drums\.sfz$/i`, que não casa arquivo
    // nenhum — o pack vinha do basic-kit por acidente, via um fallback que
    // agora é erro.
    sfz: /Programs\/01-basic-kit\.sfz$/,
    // Bateria é percussiva: a zona é a própria peça, então keyStep não se
    // aplica. O que corta tamanho aqui é dinâmica (o pack tem até 36) e cauda.
    //
    // `minVelocity` é o que faz as 6 camadas valerem alguma coisa. Sem ele,
    // elas se espalhavam por 1…127 e as três mais graves ficavam em dinâmicas
    // que o app nunca pede: medido, acento 1,00 e 0,92 escolhiam o MESMO
    // arquivo nos 15 pads, e `shaker` e `conga-mid` davam o mesmo arquivo nos
    // quatro acentos — zero dB de dinâmica num compasso inteiro.
    prune: { velocities: 6, roundRobins: 3, keyStep: 1, maxSeconds: 3.5, minVelocity: 35 },
    // Ver `convert` em fetch-samples.mjs. As camadas de dinâmica do Virtuosity
    // estao ate 19,6 dB distantes entre si, o que faz a semicolcheia fraca
    // sumir da levada; 0,6 encolhe essa distancia para 40% dela sem igualar
    // as camadas. So a bateria usa: no violao e no piano a distancia entre as
    // camadas e a dinamica do instrumento, e mexer nela seria mentir.
    layerCompression: 0.6,
    drumKit: 'acoustic',
    // Este pack é multi-microfone: cada pancada é gravada pelo microfone do
    // bumbo, o da caixa e o overhead, e os três somam. Tratá-los como round
    // robin foi o bug do timbre mudando a cada volta do loop, então a
    // condição virou verificação: se a mixagem parar de acontecer, o build
    // falha em vez de entregar o defeito de novo.
    multiMic: true,
  },
]
