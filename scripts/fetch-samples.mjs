#!/usr/bin/env node
/**
 * Baixa, poda e empacota os bancos de sample do Cadentia.
 *
 * O SFZ original fica no cache, intacto — é a fonte, e é o que um dia
 * alimenta o sfizz. O que entra no app é um manifesto JSON simples mais os
 * áudios podados, para o Kit não precisar de um parser de SFZ em tempo de
 * execução.
 *
 * Uso:
 *   node scripts/fetch-samples.mjs            # tudo
 *   node scripts/fetch-samples.mjs --download # só baixa e extrai
 *   node scripts/fetch-samples.mjs guitar-clean acoustic-piano
 *
 * O cache mora fora do repo (~/.cadentia-samples-cache) porque são
 * gigabytes de material que não pertencem ao git.
 */
import { execFileSync, spawnSync } from 'node:child_process'
import {
  existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync,
} from 'node:fs'
import { homedir } from 'node:os'
import { basename, dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PACKS, CACHE } from './samples.config.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(__dirname, '..')
const CACHE_DIR = join(homedir(), `.${CACHE}`)
// Android: a saída NÃO vai para dentro do APK (~65 MB de FLAC ficam fora do
// git e do APK base — ver goal, fase 7). Cai em `samples/` na raiz do repo,
// ignorada pelo git, de onde o QA faz `adb push` para a pasta que o
// `SampleInstall` lê, ou de onde uma entrega gerenciada empacota. O resto do
// script é o do cadentia-ios, tal qual: um pack gerado serve às duas plataformas.
const OUT_DIR = process.env.CADENTIA_SAMPLES_OUT ?? join(REPO, 'samples')

const args = process.argv.slice(2)
const downloadOnly = args.includes('--download')
const wanted = args.filter((a) => !a.startsWith('--'))
const packs = wanted.length ? PACKS.filter((p) => wanted.includes(p.id)) : PACKS

const sh = (cmd, cmdArgs, opts = {}) =>
  execFileSync(cmd, cmdArgs, { stdio: 'pipe', maxBuffer: 1 << 28, ...opts })

const mb = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`

// ── 1. baixar e extrair ────────────────────────────────────────────────

function download(pack) {
  mkdirSync(CACHE_DIR, { recursive: true })
  const file = join(CACHE_DIR, `${pack.id}-${basename(new URL(pack.url).pathname)}`)
  if (existsSync(file) && statSync(file).size > 1024) {
    console.log(`  ↓ ${pack.id}: já em cache (${mb(statSync(file).size)})`)
    return file
  }
  console.log(`  ↓ ${pack.id}: baixando…`)
  // Baixa para `.part` e só então renomeia: `curl -o` escrevendo direto no
  // destino deixava um arquivo pela metade que a próxima rodada aceitava como
  // cache válido, e a extração falhava sem que ninguém soubesse por quê.
  const part = `${file}.part`
  sh('curl', ['-fsSL', '--retry', '3', '--max-time', '3600', '-o', part, pack.url])
  renameSync(part, file)
  console.log(`  ↓ ${pack.id}: ${mb(statSync(file).size)}`)
  return file
}

/** `7zz` (7-Zip oficial, o do Mac) ou `7z` (p7zip, o do Linux) — o que houver. */
function sevenZip() {
  for (const bin of ['7zz', '7z', '7za']) {
    if (spawnSync('which', [bin]).status === 0) return bin
  }
  return '7zz'
}

function extract(pack, archive) {
  const dir = join(CACHE_DIR, pack.id)
  // O marcador guarda DE QUAL arquivo veio: trocar a `url` no config (bumpar
  // a data de release, por exemplo) baixava o arquivo novo e continuava
  // usando a árvore velha, sem aviso.
  const stamp = join(dir, '.extracted')
  if (existsSync(stamp) && readFileSync(stamp, 'utf8').includes(basename(archive))) return dir
  rmSync(dir, { recursive: true, force: true })
  mkdirSync(dir, { recursive: true })
  if (archive.endsWith('.7z')) sh(sevenZip(), ['x', '-y', `-o${dir}`, archive])
  else if (archive.endsWith('.zip')) sh('unzip', ['-q', '-o', archive, '-d', dir])
  else sh('tar', ['-xf', archive, '-C', dir])
  writeFileSync(join(dir, '.extracted'), `${basename(archive)}\n${new Date().toISOString()}\n`)
  return dir
}

/**
 * Todos os arquivos sob `dir`, recursivo e ORDENADO.
 *
 * A ordenação não é estética. `readdirSync` devolve a ordem do sistema de
 * arquivos, e o APFS devolve ordem de hash — sem ordenar, qual `.sfz` o script
 * escolhe e qual arquivo vence um empate de nome mudam entre duas extrações do
 * mesmo pacote. Um build que sorteia o timbre não é um build.
 */
function walk(dir, out = []) {
  const entries = readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
    a.name < b.name ? -1 : a.name > b.name ? 1 : 0
  )
  for (const entry of entries) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) walk(full, out)
    else out.push(full)
  }
  return out
}

// ── 2. ler o SFZ ───────────────────────────────────────────────────────

const NOTE_INDEX = { c: 0, d: 2, e: 4, f: 5, g: 7, a: 9, b: 11 }

/** Aceita "60" e "c4"/"F#3"/"bb2" — o SFZ permite os dois. */
function toMidi(token) {
  const raw = String(token).trim()
  if (/^-?\d+$/.test(raw)) return parseInt(raw, 10)
  const m = /^([a-gA-G])([#b]*)(-?\d+)$/.exec(raw)
  if (!m) return null
  let value = NOTE_INDEX[m[1].toLowerCase()]
  for (const ch of m[2]) value += ch === '#' ? 1 : -1
  return (parseInt(m[3], 10) + 1) * 12 + value
}

/**
 * Resolve `#include` recursivamente.
 *
 * O caminho é relativo à RAIZ do instrumento, não ao arquivo que inclui — foi
 * assim que o Virtuosity Drums quebrou na primeira tentativa: `mappings/x.sfz`
 * dentro de `mappings/y.sfz` aponta para `<raiz>/mappings/x.sfz`, e resolver
 * relativo ao pai daria `<raiz>/mappings/mappings/x.sfz`.
 */
function inlineIncludes(text, root, depth = 0) {
  if (depth > 12) return text
  return text.replace(/#include\s+"([^"]+)"/g, (_, rel) => {
    const candidates = [join(root, rel), join(root, basename(rel))]
    const found = candidates.find((c) => existsSync(c))
    // Include que não resolve some com metade do kit junto, em silêncio, e o
    // pack sai pela metade sem ninguém saber. Melhor parar aqui.
    if (!found) throw new Error(`#include não encontrado: "${rel}" (raiz ${root})`)
    const raw = readText(found).replace(/\/\/[^\n]*/g, ' ')
    return inlineIncludes(raw, root, depth + 1)
  })
}

/**
 * Lê texto tentando UTF-8 primeiro.
 *
 * Ler tudo como latin1 transformava `Cajón.wav` em `CajÃ³n.wav`, que não casa
 * com o nome no disco — e a região sumia contada como "amostra não
 * encontrada". Num pack de violão espanhol ou de percussão isso não é
 * hipótese.
 */
function readText(path) {
  const utf8 = readFileSync(path, 'utf8')
  return utf8.includes('�') ? readFileSync(path, 'latin1') : utf8
}

/** APFS devolve nomes em NFD e o SFZ costuma estar em NFC. */
const fold = (name) => name.normalize('NFC').toLowerCase()

/**
 * Parser de subconjunto do SFZ: `#include`, `#define`, e a herança de quatro
 * níveis `<global>` -> `<master>` -> `<group>` -> `<region>`.
 *
 * Não interpreta modulação, filtro nem curva. O que sobrevive é o que um
 * sampler de zonas precisa — e é de propósito: o SFZ completo continua no
 * cache, para o dia em que o sfizz entrar.
 */
function parseSFZ(path) {
  const root = dirname(path)
  let text = readText(path).replace(/\/\/[^\n]*/g, ' ')
  text = inlineIncludes(text, root)

  // #define $NOME valor — o Virtuosity mapeia o kit inteiro por macro.
  const defines = new Map()
  text = text.replace(/#define\s+(\$[A-Za-z0-9_]+)\s+([^\s]+)/g, (_, name, value) => {
    defines.set(name, value)
    return ' '
  })
  if (defines.size) {
    // Do mais longo para o mais curto, e com fronteira à direita: sem isso
    // `$KIT` casa dentro de `$KITROOM` e `key=$NOTEOFF` (com `$NOTE` também
    // definido) vira `key="36OFF"`, que `toMidi` não entende — a região cai
    // no default 0…127 e passa a responder por TODAS as notas, tocada a
    // dezenas de vezes a velocidade. Silenciosamente.
    const names = [...defines.keys()].sort((a, b) => b.length - a.length)
    const re = new RegExp(`(${names.map((k) => k.replace('$', '\\$')).join('|')})(?![A-Za-z0-9_])`, 'g')
    text = text.replace(re, (m) => defines.get(m) ?? m)
  }

  const tokens = text.split(/(<[a-z_]+>)/i)
  const regions = []
  const levels = { global: {}, master: {}, group: {} }
  let current = null
  let mode = 'skip'

  const applyOpcodes = (chunk, target) => {
    // `sample=` aceita espaço no caminho, então o valor vai até o próximo
    // `opcode=` em vez de até o próximo espaço.
    const re = /([a-zA-Z0-9_]+)\s*=\s*([^=]*?)(?=\s+[a-zA-Z0-9_]+\s*=|$)/gs
    let m
    while ((m = re.exec(chunk)) !== null) target[m[1].toLowerCase()] = m[2].trim()
  }

  for (const tok of tokens) {
    if (/^<[a-z_]+>$/i.test(tok)) {
      const header = tok.slice(1, -1).toLowerCase()
      if (header === 'global') { mode = 'global'; levels.global = {}; levels.master = {}; levels.group = {} }
      else if (header === 'master') { mode = 'master'; levels.master = {}; levels.group = {} }
      else if (header === 'group') { mode = 'group'; levels.group = {} }
      else if (header === 'region') {
        mode = 'region'
        current = { __inherit: { ...levels.global, ...levels.master, ...levels.group } }
        regions.push(current)
      } else mode = 'skip'
      continue
    }
    if (mode === 'skip') continue
    if (mode === 'region') { if (current) applyOpcodes(tok, current) }
    else applyOpcodes(tok, levels[mode])
  }

  return regions
    .map((r) => {
      const { __inherit, ...own } = r
      return { ...__inherit, ...own }
    })
    // Macro que sobrou sem definição é região quebrada, não região válida.
    .filter((r) => !Object.values(r).some((v) => typeof v === 'string' && v.includes('$')))
}

export { parseSFZ, toMidi, walk, download, extract, CACHE_DIR, OUT_DIR, REPO, sh, mb, packs, downloadOnly }

// ── 3. podar ───────────────────────────────────────────────────────────

/** Mantém `keep` itens espalhados por igual, sempre com o primeiro e o último. */
function spread(items, keep) {
  if (keep >= items.length) return items
  if (keep <= 1) return [items[items.length - 1]]
  const out = []
  for (let i = 0; i < keep; i++) {
    out.push(items[Math.round((i * (items.length - 1)) / (keep - 1))])
  }
  return [...new Set(out)]
}

/**
 * O índice da VARIAÇÃO — e essa distinção é a correção mais importante aqui.
 *
 * No SFZ, regiões que casam a mesma nota, a mesma dinâmica E a mesma posição
 * de sequência disparam JUNTAS: é assim que uma bateria multi-microfone soa,
 * o microfone do bumbo mais o da caixa mais o overhead, somados. Regiões que
 * diferem no `seq_position` (ou na faixa de `lorand`) é que são alternativas.
 *
 * A primeira versão tratou os microfones como round robin. O resultado foi
 * exatamente o que se ouviu no aparelho: a caixa mudava de timbre a cada
 * volta do loop, porque a cada volta tocava um microfone diferente sozinho.
 */
function rrIndex(region) {
  if (region.seq_position != null) return parseInt(region.seq_position, 10)
  // A faixa aleatória identifica a alternativa pelo PAR, nunca por uma ponta
  // só. Olhar apenas para `lorand` (ou apenas para `hirand`) colide sempre que
  // uma variação termina onde a outra começa, que é como todo SFZ escreve um
  // round robin aleatório:
  //
  //     <region> hirand=0.250            → primeira variação
  //     <region> lorand=0.250 hirand=0.5 → segunda variação
  //
  // Medido no Growlybass: as duas caíam no balde 250, e o `amix` SOMOU os dois
  // takes. O arquivo `bass-fingered/0038.flac` entregue correlacionava
  // r = +1,0000 com `a4_ff_rr1.wav + a4_ff_rr2.wav` — o melhor take sozinho
  // chegava a +0,9618. O resultado audível: a variação 0 saía 5 dB mais alta
  // que a 1 (pior caso 10,25 dB), com flam e 8 arquivos achatados no teto.
  if (region.lorand != null || region.hirand != null) {
    const lo = Math.round(parseFloat(region.lorand ?? 0) * 1000)
    const hi = Math.round(parseFloat(region.hirand ?? 1) * 1000)
    return lo * 1009 + hi
  }
  // Sem nada disso, a região está na posição 1 da sequência. Tem que ser um
  // valor CONSTANTE: usar o índice da região deu a cada microfone da primeira
  // pancada uma "variação" própria, e a mixagem multi-microfone desabou de 291
  // para 118 pancadas — a mesma classe do bug original, por outra porta.
  return 1
}

/**
 * O que NÃO é uma pancada tocável.
 *
 * Sem este filtro, tudo que o parser não entende acaba tratado como "mais um
 * microfone" e é somado ao ataque: a região que o autor desativou com
 * `end=-1`, a região de choke que aponta para `*silence`, e o ruído de
 * soltura de `trigger=release`. O Virtuosity tem nove de cada uma das duas
 * primeiras.
 */
function isPlayable(region) {
  if (!region.sample) return false
  if (String(region.sample).includes('*silence')) return false
  if (region.end != null && parseInt(region.end, 10) < 0) return false
  if (region.trigger && region.trigger !== 'attack') return false
  return true
}

function normalize(region) {
  const key = region.key != null ? toMidi(region.key) : null
  // `key=` que não vira nota é macro corrompida, não região válida. Deixar cair
  // no default daria uma zona de 0 a 127 respondendo por tudo.
  if (region.key != null && key == null) return null
  const lo = key ?? (region.lokey != null ? toMidi(region.lokey) : 0)
  const hi = key ?? (region.hikey != null ? toMidi(region.hikey) : 127)
  const root = region.pitch_keycenter != null ? toMidi(region.pitch_keycenter) : (key ?? lo)
  return {
    sample: region.sample,
    lo, hi, root,
    vlo: region.lovel != null ? parseInt(region.lovel, 10) : 1,
    vhi: region.hivel != null ? parseInt(region.hivel, 10) : 127,
    rr: rrIndex(region),
    volume: region.volume != null ? parseFloat(region.volume) : 0,
    tune: (region.tune != null ? parseFloat(region.tune) : 0)
      + (region.pitch != null ? parseFloat(region.pitch) : 0),
    pan: region.pan != null ? parseFloat(region.pan) / 100 : 0,
    offset: region.offset != null ? parseInt(region.offset, 10) : 0,
    loopMode: (region.loop_mode || region.loopmode || '').toLowerCase(),
    loopStart: region.loop_start != null ? parseInt(region.loop_start, 10) : null,
    loopEnd: region.loop_end != null ? parseInt(region.loop_end, 10) : null,
  }
}

/**
 * Poda o catálogo e devolve UNIDADES, não regiões.
 *
 * Uma unidade é o conjunto de regiões que soam ao mesmo tempo — normalmente
 * os microfones de uma mesma pancada. Elas viram um arquivo só na conversão,
 * o que conserta o timbre e ainda corta o trabalho de decodificação por
 * pancada de N para um.
 */
function prune(regions, cfg) {
  // Nota que nenhum pad alcança é peso morto no bundle.
  //
  // O Virtuosity mapeia a percussão inteira do GM — agogô, cuíca, apito,
  // sino de árvore — e o Cadentia tem 15 pads. Medido no pack entregue: 237
  // dos 360 arquivos (19,9 MB de 30,2 MB) estavam em notas que nenhum pad
  // toca. Não é só tamanho: é tamanho que o usuário baixa e nunca ouve.
  if (cfg.keepNotes) {
    const wanted = new Set(cfg.keepNotes)
    const before = regions.length
    regions = regions.filter((r) => {
      for (let n = r.lo; n <= r.hi; n += 1) if (wanted.has(n)) return true
      return false
    })
    if (before !== regions.length) {
      console.log(`  · ${before - regions.length} regiões em notas sem pad, fora`)
    }
  }

  // Camada de dinâmica que o app nunca pede também é peso morto — e pior,
  // rouba as vagas de `velocities` das camadas que ele pede. O sequenciador
  // nunca desce de 0,62 (≈ MIDI 79) e o dedo no pad vale 1,0; a camada mais
  // grave que chega a ser escolhida é a que cobre MIDI 35. Manter as camadas
  // de sussurro do Virtuosity gastava 6 vagas para cobrir 1…127 quando o que
  // se ouve mora em 35…127.
  if (cfg.minVelocity) {
    const before = regions.length
    regions = regions.filter((r) => r.vhi >= cfg.minVelocity)
    if (before !== regions.length) {
      console.log(`  · ${before - regions.length} regiões abaixo de vel ${cfg.minVelocity}, fora`)
    }
  }

  const byZone = new Map()
  for (const r of regions) {
    const zone = `${r.lo}-${r.hi}-${r.root}`
    if (!byZone.has(zone)) byZone.set(zone, [])
    byZone.get(zone).push(r)
  }

  // Zonas: uma a cada `keyStep` semitons. O resto vem por transposição.
  let zones = [...byZone.entries()].sort((a, b) => a[1][0].root - b[1][0].root)
  if (cfg.keyStep > 1) {
    const kept = []
    let last = -999
    for (const z of zones) {
      if (z[1][0].root - last >= cfg.keyStep) { kept.push(z); last = z[1][0].root }
    }
    // A zona mais aguda nunca some: sem ela o topo do braço vira transposição
    // de meio braço de distância, que soa como fita acelerada.
    if (kept[kept.length - 1] !== zones[zones.length - 1]) kept.push(zones[zones.length - 1])
    zones = kept
  }

  const units = []
  for (const [, inZone] of zones) {
    const byVel = new Map()
    for (const r of inZone) {
      const v = `${r.vlo}-${r.vhi}`
      if (!byVel.has(v)) byVel.set(v, [])
      byVel.get(v).push(r)
    }
    let velKeys = [...byVel.keys()].sort((a, b) => parseInt(a, 10) - parseInt(b, 10))
    // A camada MAIS FORTE de um instrumento melódico é um efeito, e não uma
    // dinâmica.
    //
    // O Growlybass tem quatro camadas (1–60, 61–95, 96–120, 121–127) e a poda
    // guardava duas. `spread` pega os EXTREMOS, então sobravam pianíssimo e
    // fortíssimo, sem nada no meio — e qualquer toque acima de metade da força
    // caía no fortíssimo. Medido nas duas: a camada forte tem 0,0984 de energia
    // acima de 2 kHz nos primeiros 40 ms contra 0,0069 da leve, **catorze vezes
    // mais agudo no ataque**, e com o pico grampeado em 1,000. É isso que soa
    // como slap, e era o som padrão de tocar normal.
    //
    // Para bateria a camada mais forte é justamente o acento que se quer, então
    // isto é opção de pack e não regra geral.
    if (cfg.dropTopVelocity && velKeys.length > cfg.velocities) {
      const dropped = velKeys.pop()
      console.log(`  · camada de dinâmica ${dropped} (a mais forte) fora: é efeito, não dinâmica`)
    }
    const vels = spread(velKeys, cfg.velocities)
    for (const v of vels) {
      // Agrupa por variação: cada grupo é uma pancada, com todos os seus
      // microfones dentro.
      const bySeq = new Map()
      for (const r of byVel.get(v)) {
        if (!bySeq.has(r.rr)) bySeq.set(r.rr, [])
        bySeq.get(r.rr).push(r)
      }
      const seqs = spread([...bySeq.keys()].sort((a, b) => a - b), cfg.roundRobins)
      seqs.forEach((seq, i) => units.push({ rr: i, layers: bySeq.get(seq) }))
    }
  }
  return units
}

// ── 4. converter e escrever ────────────────────────────────────────────

/** Pads do Cadentia → nota GM de percussão, para casar com o SFZ da bateria. */
const DRUM_PAD_NOTE = {
  kick: 36, snare: 38, 'hihat-c': 42, 'hihat-o': 46,
  crash: 49, ride: 51, rim: 37,
  // O kit básico do Virtuosity tem dois toms, não três. O do meio pega a
  // batida fora do centro do tom grave: mesmo tambor, timbre diferente, que
  // é exatamente o que uma bateria de dois toms entrega na vida real.
  'tom-low': 41, 'tom-mid': 43, 'tom-high': 48,
  cowbell: 56, shaker: 82,
  // GM: 64 conga grave, 62 conga aguda abafada, 63 conga aguda aberta.
  'conga-low': 64, 'conga-mid': 62, 'conga-high': 63,
  // `clap` não tem equivalente no Virtuosity — a nota 39 ali é a caixa fora
  // do centro. Fica de fora, e o pad continua na síntese, que tem um clap
  // de verdade. Sample errado é pior que síntese honesta.
}

/**
 * Monta a cadeia de filtros de uma pancada: cada camada com o seu ganho, todas
 * subidas a estéreo, somadas sem renormalizar.
 *
 * `channel_layouts=stereo` no `aformat` não é detalhe. Sem ele o `amix`
 * negocia o grafo inteiro pelo layout da PRIMEIRA entrada, e uma camada mono
 * na frente dobrava a mixagem toda para mono — 286 dos 360 arquivos de
 * bateria saíram mono na primeira geração, num app cujo próprio motor de
 * bateria diz que "mono foi a maior razão de o som antigo parecer de
 * brinquedo".
 */
function mixChain(layers, extra = '') {
  const chain = layers
    .map((l, i) => {
      const trim = l.offset > 0 ? `atrim=start_sample=${l.offset},asetpts=N/SR/TB,` : ''
      const gain = l.volume ? `volume=${l.volume}dB,` : ''
      return `[${i}:a]${trim}${gain}aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a${i}]`
    })
    .join(';')
  const mix = layers.length > 1
    ? `${layers.map((_, i) => `[a${i}]`).join('')}amix=inputs=${layers.length}:normalize=0[m]`
    : '[a0]anull[m]'
  return `${chain};${mix}${extra}`
}

/** Pico verdadeiro, em dB. `astats` mede em ponto flutuante; `volumedetect`
 *  monta histograma de inteiro e SATURA em 0 dB — mediu 0.0 para um sinal a
 *  +20 dB, e foi por isso que o ganho do pack saiu 0.89 em seis dos sete
 *  packs: 0.89 é o que se calcula quando o pico medido é exatamente 1,0. */
function truePeak(layers, seconds) {
  const ff = ['-hide_banner', '-nostats', '-loglevel', 'info']
  for (const l of layers) ff.push('-i', l.src)
  ff.push('-filter_complex', `${mixChain(layers)};[m]astats=measure_perchannel=none[out]`)
  ff.push('-map', '[out]', '-t', String(seconds), '-f', 'null', '-')
  const run = spawnSync('ffmpeg', ff, { encoding: 'utf8', maxBuffer: 1 << 26 })
  const m = /Peak level dB:\s*(-?[\d.]+)/.exec(run.stderr || '')
  return m ? parseFloat(m[1]) : null
}

function convert(pack, units, dir, outDir) {
  // Chave por CAMINHO relativo, não por nome de arquivo. Dois microfones com
  // o mesmo nome em pastas diferentes é o que `default_path` produz num pack
  // multi-mic; com chave por nome, o último da travessia vence e as N camadas
  // resolvem para o MESMO arquivo — um microfone some, o outro dobra, e a
  // verificação de mixagem passa verde porque ela conta camadas, não arquivos.
  const byPath = new Map()
  const byName = new Map()
  for (const f of walk(dir)) {
    byPath.set(fold(relative(dir, f).replace(/\\/g, '/')), f)
    const key = fold(basename(f))
    byName.set(key, byName.has(key) ? null : f) // null = ambíguo
  }
  const resolve = (sample) => {
    const clean = String(sample).replace(/\\/g, '/')
    for (const [key, file] of byPath) {
      if (key.endsWith(fold(clean).replace(/^(\.\.\/)+/, ''))) return file
    }
    return byName.get(fold(basename(clean))) ?? null
  }

  const manifest = []
  let index = 0
  let missing = 0
  let mixed = 0
  const levels = new Map()

  // Primeira passada: o pico verdadeiro do pack inteiro. Um ganho só, e não
  // um por arquivo — normalizar arquivo a arquivo igualaria a pancada fraca à
  // forte e mataria a dinâmica que motivou trocar síntese por gravação.
  const resolved = []
  for (const unit of units) {
    const layers = unit.layers.map((r) => ({ ...r, src: resolve(r.sample) })).filter((l) => l.src)
    if (!layers.length) { missing++; continue }
    resolved.push({ ...unit, layers })
  }
  const peaks = resolved.map((unit) => truePeak(unit.layers, pack.prune.maxSeconds))

  // Aproxima as camadas de dinâmica de uma mesma peça — sem igualá-las.
  //
  // Isto NÃO é normalizar arquivo a arquivo (ver o parágrafo acima; aquilo
  // mataria a dinâmica). É encolher a distância entre as camadas de UMA
  // mesma zona, porque num pack podado ela fica grande demais para os quatro
  // acentos que o sequenciador produz. Medido no Virtuosity com seis camadas:
  // o chimbal aberto caía 19,60 dB do acento 1,00 para o 0,62, e a caixa
  // 14,32 dB — a semicolcheia fraca sumia da levada. A síntese, que é a
  // referência que o founder ouve ao lado, entrega 4,06 dB.
  //
  // Com 0,6 a distância cai para 40% da original: o chimbal fica em ~7,8 dB
  // de camada mais os 3,1 dB da curva contínua do app, que é a faixa em que
  // uma levada de rock realmente vive. O timbre da camada fraca continua
  // sendo o da pancada fraca — é o que uma camada de velocity serve —, só o
  // volume passa a ser nosso. As variações de round robin da mesma camada
  // entram no mesmo cálculo, o que de quebra encolhe os 6,5 dB entre os takes
  // de conga que a auditoria mediu.
  const compress = pack.layerCompression ?? 0
  const boosts = resolved.map(() => 0)
  if (compress > 0) {
    const byZone = new Map()
    resolved.forEach((unit, i) => {
      if (peaks[i] == null) return
      const zone = `${unit.layers[0].lo}-${unit.layers[0].hi}-${unit.layers[0].root}`
      if (!byZone.has(zone)) byZone.set(zone, [])
      byZone.get(zone).push(i)
    })
    for (const indices of byZone.values()) {
      const ref = Math.max(...indices.map((i) => peaks[i]))
      for (const i of indices) boosts[i] = (ref - peaks[i]) * compress
    }
    const most = Math.max(0, ...boosts)
    if (most > 0) console.log(`  · camadas aproximadas em ${compress.toFixed(2)} (até +${most.toFixed(1)} dB)`)
  }

  // A folga sai do pico DEPOIS do reforço, senão o reforço reintroduz o
  // ceifamento que ela existe para evitar.
  let worst = -120
  resolved.forEach((_, i) => {
    if (peaks[i] != null) worst = Math.max(worst, peaks[i] + boosts[i])
  })
  // Folga de 1 dB abaixo do fundo de escala, aplicada ANTES de virar inteiro
  // de 16 bits — depois já não adianta, o ceifamento está gravado.
  const trimDb = Math.min(0, -1 - worst)

  // Segunda passada: escreve.
  for (const [order, unit] of resolved.entries()) {
    const layers = unit.layers
    if (layers.length > 1) mixed++
    const head = layers[0]
    const name = `${String(index).padStart(4, '0')}.flac`
    const dst = join(outDir, name)

    // O corte: uma nota com loop precisa chegar inteira até o fim do loop,
    // senão o loop é descartado e a nota morre num degrau. Treze dos dezesseis
    // arquivos de órgão terminavam exatamente no teto de 4 s com 96% de
    // amplitude — um estalo, no único instrumento do catálogo que sustenta.
    const loopEndSeconds = head.loopMode?.startsWith('loop') && head.loopEnd != null
      ? (head.loopEnd - head.offset) / 44100 + 0.05
      : 0
    const seconds = Math.max(pack.prune.maxSeconds, loopEndSeconds)

    const post = [`volume=${(trimDb + boosts[order]).toFixed(2)}dB`]
    // Sem loop, o corte ganha uma rampa: cortar seco no meio de uma nota é um
    // clique, e num acorde de seis cordas são seis cliques juntos.
    if (!loopEndSeconds) post.push(`afade=t=out:st=${Math.max(0, seconds - 0.03).toFixed(3)}:d=0.03`)

    const ff = ['-y', '-hide_banner', '-nostats', '-loglevel', 'error']
    for (const l of layers) ff.push('-i', l.src)
    ff.push('-filter_complex', `${mixChain(layers)};[m]${post.join(',')}[out]`, '-map', '[out]')
    ff.push('-t', String(seconds), '-ar', '44100', '-ac', '2', '-sample_fmt', 's16', dst)
    spawnSync('ffmpeg', ff, { encoding: 'utf8', maxBuffer: 1 << 26 })
    if (!existsSync(dst)) { missing++; continue }

    const slot = `${head.lo}-${head.hi}-${head.vlo}-${head.vhi}`
    if (!levels.has(slot)) levels.set(slot, [])
    const db = truePeak(layers, seconds)
    if (db != null) levels.get(slot).push(db + trimDb + boosts[order])

    const entry = {
      f: name, lo: head.lo, hi: head.hi, root: head.root,
      vlo: head.vlo, vhi: head.vhi, rr: unit.rr,
    }
    if (head.tune) entry.tune = Number(head.tune.toFixed(1))
    if (head.pan) entry.pan = Number(head.pan.toFixed(3))
    if (loopEndSeconds) {
      const start = head.loopStart - head.offset
      const end = head.loopEnd - head.offset
      if (start >= 0 && end > start) entry.loop = [start, end]
    }
    manifest.push(entry)
    index++
  }

  // O maior espalhamento de nível entre as variações de uma mesma peça na
  // mesma dinâmica. Variações de um mesmo tambor ficam em 1 ou 2 dB; foi
  // quando esse número passava de 20 dB — porque cada "variação" era um
  // microfone diferente — que a caixa mudava de som a cada volta do loop.
  //
  // O piso de -30 dBFS não é frouxidão: em nível de sussurro, dois takes de
  // uma conga podem estar 20 dB distantes em decibel e serem os dois
  // inaudíveis. Medir espalhamento relativo perto do silêncio mede ruído de
  // medição. O que se quer flagrar é diferença que se ouve.
  const spreads = [...levels.entries()]
    .filter(([, dbs]) => dbs.length > 1 && Math.max(...dbs) > -30)
    .map(([slot, dbs]) => ({ slot, spread: Math.max(...dbs) - Math.min(...dbs) }))
    .sort((a, b) => b.spread - a.spread)
  const spread = spreads.length ? spreads[0].spread : null
  return { manifest, missing, mixed, spread, spreads, trimDb: Number(trimDb.toFixed(2)) }
}

/**
 * Magnitude de UMA frequência num bloco de amostras, por Goertzel.
 *
 * Goertzel em vez de FFT porque a pergunta aqui tem três frequências, não mil:
 * é um filtro ressonante de duas linhas, sem dependência e sem alocar.
 */
function goertzel(samples, freq, rate) {
  const k = (2 * Math.PI * freq) / rate
  const coeff = 2 * Math.cos(k)
  let s1 = 0
  let s2 = 0
  for (let i = 0; i < samples.length; i += 1) {
    const s = samples[i] + coeff * s1 - s2
    s2 = s1
    s1 = s
  }
  return Math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2)
}

/**
 * A amostra soa na altura que o manifesto promete?
 *
 * Isto existe porque um pack inteiro saiu uma oitava abaixo do declarado e
 * ninguém percebeu por semanas: o `growlybass_clean.sfz` diz
 * `pitch_keycenter=69` para um arquivo cujo fundamental é 220 Hz. Com sample
 * ligado o baixo tocava uma oitava abaixo do sintetizado, na mesma nota.
 *
 * O teste NÃO é "existe energia em f/2" — isso reprova o órgão honestamente,
 * porque um registro de drawbar com 16' puxado tem energia em f/2 de
 * propósito. O que separa os dois casos é o parcial 2,5·f: ele só existe se o
 * fundamental verdadeiro for f/2 (a série f/2, f, 1,5f, 2f, 2,5f). Medido: no
 * baixo errado ele estava presente; no órgão, 67 dB abaixo.
 */
function verifyPitch(pack, manifest, outDir) {
  const midiHz = (m) => 440 * 2 ** ((m - 69) / 12)
  // Só regiões tocadas na própria raiz, espalhadas pela extensão.
  // Fora as pontas do teclado. No grave de um piano de verdade o parcial de
  // f/2 existe por inarmonicidade e pela caixa — medido, as raízes 24 e 30 do
  // Upright Piano KW, que estão CERTAS, disparavam o teste. No agudo a
  // fundamental encosta no limite da janela. O que sobra é a faixa onde a
  // pergunta tem resposta.
  const roots = manifest.filter((r) => r.root >= r.lo && r.root <= r.hi && r.root >= 36 && r.root <= 84)
  if (!roots.length) return
  const step = Math.max(1, Math.floor(roots.length / 8))
  const sampled = roots.filter((_, i) => i % step === 0).slice(0, 8)

  const offenders = []
  let inconclusive = 0
  for (const region of sampled) {
    const expected = midiHz(region.root)
    let raw
    try {
      raw = sh('ffmpeg', [
        '-v', 'error', '-i', join(outDir, region.f),
        '-ac', '1', '-ar', '44100', '-t', '1.0', '-f', 'f32le', '-',
      ])
    } catch {
      continue
    }
    const pcm = new Float32Array(raw.buffer, raw.byteOffset, Math.floor(raw.byteLength / 4))
    // O ataque tem ruído de palheta e transiente; a altura mora no sustento.
    const from = Math.min(pcm.length, 4410)
    const block = pcm.subarray(from, Math.min(pcm.length, from + 32768))
    if (block.length < 8192) continue
    const db = (x) => 20 * Math.log10(Math.max(x, 1e-12))
    const atRoot = db(goertzel(block, expected, 44100))
    const atHalf = db(goertzel(block, expected / 2, 44100))
    const atTwoAndHalf = db(goertzel(block, expected * 2.5, 44100))
    if (atHalf > atRoot - 3) inconclusive += 1
    if (atHalf > atRoot - 3 && atTwoAndHalf > atRoot - 40) {
      offenders.push(
        `${region.f} raiz ${region.root} (${expected.toFixed(1)} Hz): ` +
          `f/2 ${(atHalf - atRoot).toFixed(1)} dB, 2,5f ${(atTwoAndHalf - atRoot).toFixed(1)} dB`
      )
    }
  }
  if (offenders.length > sampled.length / 3) {
    throw new Error(
      `afinação: ${offenders.length} de ${sampled.length} amostras soam uma oitava abaixo da raiz declarada.\n` +
        `    Use keyOffset no pack se a fonte declara errado.\n    ${offenders.join('\n    ')}`
    )
  }
  // Diz a VERDADE quando não dá para decidir.
  //
  // Um órgão com o registro de 16' puxado tem, de propósito, mais energia em
  // f/2 do que em f. Nesse caso o espectro de um mapeamento certo e o de um
  // errado por uma oitava são o MESMO espectro — não há o que medir, porque a
  // diferença está só no rótulo. Verificado: simulando o erro do Growlybass no
  // órgão, o teste não acusa nada (o parcial de 2,5f fica 42 a 76 dB abaixo
  // nos oito). Imprimir "conferida" aqui seria mentir com um número.
  const subOctave = inconclusive > sampled.length / 2
  if (subOctave) {
    console.log(
      `  ⚠︎ afinação NÃO verificável: ${inconclusive} de ${sampled.length} amostras têm sub-oitava`
    )
    console.log('    (registro com 16\' — espectro certo e errado por uma oitava são iguais)')
    return
  }
  console.log(`  · afinação conferida em ${sampled.length} amostras`)
}

/** Nível RMS de um arquivo já entregue, em dBFS. */
function fileLevel(path) {
  const out = spawnSync(
    'ffmpeg', ['-hide_banner', '-i', path, '-af', 'astats=metadata=1:reset=0', '-f', 'null', '-'],
    { encoding: 'utf8', maxBuffer: 1 << 26 }
  ).stderr || ''
  const values = out.split('\n')
    .filter((l) => l.includes('RMS level dB') && !l.includes('Overall'))
    .map((l) => parseFloat(l.split(':').pop()))
    .filter((v) => Number.isFinite(v))
  return values.length ? Math.max(...values) : null
}

/**
 * A levada tem dinâmica — e ela cabe num intervalo que se ouve?
 *
 * A verificação é DOS DOIS LADOS porque este número já errou nas duas
 * direções, e cada erro tem o seu som:
 *
 *   · plano demais (medido: 0,00 dB no shaker e na conga-mid, com o acento
 *     1,00 e o 0,92 caindo no mesmo arquivo nos quinze pads) soa a caixa de
 *     ritmo de teclado dos anos oitenta;
 *   · fundo demais (medido: 19,60 dB no chimbal aberto, 14,32 na caixa) faz a
 *     semicolcheia fraca sumir, e a levada soa como se estivesse perdendo
 *     notas.
 *
 * A referência é a síntese, que entrega 4,06 dB entre o acento cheio e o
 * fraco e toca ao lado do sample no mesmo app.
 *
 * Reproduz aqui a escolha que `SampleSelection.region` faz no app. Duas
 * implementações da mesma regra é uma dívida real — mas a alternativa era não
 * medir, e foi não medir que deixou os dois defeitos passarem.
 */
function verifyDrumDynamics(pack, manifest, outDir) {
  const padNotes = DRUM_PAD_NOTE
  // O mapa de acentos de `DrumSequencer.accent(forStep:)`.
  const accents = [1.0, 0.92, 0.76, 0.62]
  const midi = (v) => Math.round(Math.min(1, Math.max(0, v)) * 126) + 1
  const mid = (r) => Math.floor((r.vlo + r.vhi) / 2)
  const pick = (note, velocity) => {
    const inZone = manifest.filter((r) => note >= r.lo && note <= r.hi)
    if (!inZone.length) return null
    const v = midi(velocity)
    let inVel = inZone.filter((r) => v >= r.vlo && v <= r.vhi)
    if (!inVel.length) {
      const nearest = inZone.reduce((a, b) => (Math.abs(mid(a) - v) <= Math.abs(mid(b) - v) ? a : b))
      inVel = inZone.filter((r) => r.vlo === nearest.vlo && r.vhi === nearest.vhi)
    }
    return inVel.sort((a, b) => a.rr - b.rr)[0]
  }
  // `SampleBank.drumVelocityGain`, em dB.
  const curveDb = (v) => 20 * Math.log10(v ** 0.75)

  const spreads = []
  for (const [pad, note] of Object.entries(padNotes)) {
    const levels = []
    for (const accent of accents) {
      const region = pick(note, accent)
      if (!region) { levels.length = 0; break }
      const db = fileLevel(join(outDir, region.f))
      if (db == null) { levels.length = 0; break }
      levels.push(db + curveDb(accent))
    }
    if (levels.length === accents.length) {
      spreads.push({ pad, spread: Math.max(...levels) - Math.min(...levels) })
    }
  }
  if (!spreads.length) return
  spreads.sort((a, b) => b.spread - a.spread)
  const mean = spreads.reduce((s, x) => s + x.spread, 0) / spreads.length
  const widest = spreads[0]
  console.log(
    `  · dinâmica da levada: média ${mean.toFixed(2)} dB · maior ${widest.spread.toFixed(2)} dB (${widest.pad})`
  )
  if (widest.spread > 14) {
    throw new Error(
      `dinâmica funda demais: ${widest.pad} cai ${widest.spread.toFixed(1)} dB do acento cheio ao fraco.\n` +
        '    A semicolcheia fraca some da levada. Aumente layerCompression no pack.'
    )
  }
  if (mean < 1.5) {
    throw new Error(
      `dinâmica plana demais: média de ${mean.toFixed(2)} dB entre o acento cheio e o fraco.\n` +
        '    Diminua layerCompression, ou minVelocity está cortando camadas demais.'
    )
  }
}

function build(pack) {
  console.log(`\n▸ ${pack.id} — ${pack.name}`)
  const archive = download(pack)
  const dir = extract(pack, archive)

  // Sem fallback. A versão anterior caía no primeiro `.sfz` que a varredura
  // encontrasse quando o padrão não casava — e foi o que aconteceu com o
  // Virtuosity por meses: `sfz: /Virtuosity Drums\.sfz$/i` não existe no
  // arquivo, e o pack inteiro veio de `01-basic-kit.sfz` por acidente. Deu
  // certo por sorte; o mesmo acidente com outro pack teria embutido o programa
  // errado sem uma linha de aviso. Padrão que não casa agora é erro.
  const sfzPath = walk(dir).find((f) => pack.sfz.test(f))
  if (!sfzPath) {
    const candidates = walk(dir).filter((f) => f.endsWith('.sfz')).map((f) => basename(f))
    throw new Error(
      `nenhum .sfz casa ${pack.sfz} em ${dir}. Encontrados: ${candidates.slice(0, 12).join(', ')}` +
        (candidates.length > 12 ? ` (+${candidates.length - 12})` : '')
    )
  }
  console.log(`  · sfz: ${basename(sfzPath)}`)

  const raw = parseSFZ(sfzPath).filter(isPlayable)
  let normalized = raw.map(normalize).filter(Boolean)

  // Amostras que não são notas. O Growlybass mapeia raspadas de palheta nas
  // teclas 81–84; a regra "a zona mais aguda nunca some" as preservava, e o
  // topo do baixo virava ruído. `0051.flac` (raiz 84 = C6, 1046 Hz) não tinha
  // série harmônica nenhuma: um aglomerado entre 84 e 140 Hz.
  if (pack.excludeSample) {
    const before = normalized.length
    normalized = normalized.filter((r) => !pack.excludeSample.test(String(r.sample)))
    if (before !== normalized.length) {
      console.log(`  · ${before - normalized.length} regiões fora por ${pack.excludeSample}`)
    }
  }

  // Correção de oitava da FONTE, não nossa.
  //
  // O `growlybass_clean.sfz` declara `pitch_keycenter=69` (lá 440 Hz) para um
  // arquivo cujo fundamental medido é 220,21 Hz — o parcial de 220 Hz está
  // 7,1 dB ACIMA do de 440. O readme da biblioteca confirma: a nota mais grave
  // gravada é C#1 (34,65 Hz), mapeada em 37 (C#2). Todo o pack está uma oitava
  // acima do que soa, então tocar a mesma nota com sample e com síntese dava
  // duas oitavas diferentes. A verificação de afinação abaixo trava isso.
  if (pack.keyOffset) {
    normalized = normalized.map((r) => ({
      ...r, lo: r.lo + pack.keyOffset, hi: r.hi + pack.keyOffset, root: r.root + pack.keyOffset,
    }))
    console.log(`  · afinação da fonte corrigida em ${pack.keyOffset} semitons`)
  }

  // `keepNotes` sai do próprio mapa de pads, nunca de uma lista paralela na
  // config: pad novo entra na poda no mesmo commit em que entra no app.
  const units = prune(normalized, {
    ...pack.prune,
    keepNotes: pack.drumKit ? Object.values(DRUM_PAD_NOTE) : undefined,
  })
  const layers = units.reduce((sum, u) => sum + u.layers.length, 0)
  console.log(`  · regiões: ${normalized.length} → ${layers} em ${units.length} pancadas`)

  const outDir = join(OUT_DIR, pack.id)
  rmSync(outDir, { recursive: true, force: true })
  mkdirSync(outDir, { recursive: true })

  const { manifest, missing, mixed, spread, spreads, trimDb } = convert(pack, units, dir, outDir)
  if (spreads?.length) {
    const worst = spreads.slice(0, 4).map((s) => `${s.slot}:${s.spread.toFixed(1)}dB`).join('  ')
    console.log(`  · variações mais desiguais — ${worst}`)
  }
  if (missing) console.log(`  · ${missing} pancadas sem arquivo (puladas)`)
  if (mixed) console.log(`  · ${mixed} pancadas com mais de um microfone, mixadas`)
  console.log(
    `  · folga aplicada: ${trimDb} dB · variações ${spread == null ? 'não medidas (uma por peça)' : `dentro de ${spread.toFixed(1)} dB`}`
  )

  // Pack vazio é falha, não resultado. Sem isto, a verificação de `multiMic`
  // abaixo vira `0 < 0` (falso) e um pack sem uma única amostra é empacotado,
  // listado no índice do app, com código de saída zero.
  if (!manifest.length) throw new Error('nenhuma amostra convertida')

  // Bateria não tem altura declarada para conferir; melódico tem. Em
  // compensação, a bateria é a única com mapa de acentos para verificar.
  if (pack.drumKit) verifyDrumDynamics(pack, manifest, outDir)
  else verifyPitch(pack, manifest, outDir)

  // Ver `multiMic` em samples.config.mjs: a mixagem dos microfones é o que
  // mantém o timbre igual entre as voltas do loop. Metade das pancadas é um
  // piso folgado — o que se quer detectar é ela cair para zero.
  if (pack.multiMic && mixed < manifest.length / 2) {
    throw new Error(
      `mixagem de microfones sumiu: só ${mixed} de ${manifest.length} pancadas têm mais de uma camada`
    )
  }
  // O espalhamento de nível é AVISO, não erro — e a distinção importa.
  //
  // A verificação acima é estrutural e exata: ou os microfones foram mixados,
  // ou não foram. Já o espalhamento mede a fonte, não o nosso trabalho: os
  // takes mais fracos de uma conga na camada de sussurro do Virtuosity estão
  // 21 dB distantes entre si porque o percussionista tocou assim, e essa
  // camada nem é alcançável pelo app (o sequenciador nunca desce de 0,62 de
  // dinâmica, e o dedo no pad vale 1,0). Derrubar o build por causa disso
  // seria bloquear por um defeito que não é nosso e que ninguém ouve.
  if (spread != null && spread > 12) {
    console.log(`  ⚠︎ variação de até ${spread.toFixed(1)} dB entre takes — da gravação, não da mixagem`)
  }

  const doc = {
    id: pack.id,
    voice: pack.voice,
    name: pack.name,
    license: pack.license,
    licenseURL: pack.licenseURL,
    source: pack.source,
    kind: pack.drumKit ? 'drums' : 'melodic',
    sampleRate: 44100,
    padNotes: pack.drumKit ? DRUM_PAD_NOTE : undefined,
    regions: manifest,
  }
  writeFileSync(join(outDir, 'manifest.json'), `${JSON.stringify(doc)}\n`)

  // A licença viaja junto — o TEXTO dela, não um link para ele.
  //
  // Isto já foi um cabeçalho de 200 bytes com duas URLs e a frase "página
  // some; prova fica" logo acima. As duas coisas não podiam ser verdade ao
  // mesmo tempo: se a página sumisse, o arquivo não guardava nada. Agora o
  // texto que veio no pacote de origem é copiado para dentro, e o cabeçalho
  // vira o que sempre devia ter sido — a procedência do que está abaixo dele.
  const upstream = walk(dir)
    .filter((f) => /(^|\/)(license|licence|copying|readme)([^/]*)$/i.test(f))
    .sort((a, b) => {
      const rank = (f) => (/(license|licence|copying)/i.test(basename(f)) ? 0 : 1)
      return rank(a) - rank(b) || a.length - b.length
    })[0]
  const header = [
    pack.name, '', `Licença: ${pack.license}`, `Texto: ${pack.licenseURL}`,
    `Fonte: ${pack.source}`, `Arquivado em: ${new Date().toISOString().slice(0, 10)}`, '',
  ].join('\n')
  const body = upstream
    ? `${'─'.repeat(70)}\nTexto original, copiado de ${basename(upstream)}:\n${'─'.repeat(70)}\n\n${readText(upstream)}\n`
    : '(O pacote de origem não trazia arquivo de licença; ver a URL acima.)\n'
  writeFileSync(join(outDir, 'LICENSE.txt'), header + body)

  const bytes = walk(outDir).reduce((sum, f) => sum + statSync(f).size, 0)
  console.log(`  · ${manifest.length} amostras, ${mb(bytes)}`)
  return { id: pack.id, samples: manifest.length, bytes }
}

// ── main ───────────────────────────────────────────────────────────────

if (process.argv[1] && process.argv[1].endsWith('fetch-samples.mjs')) {
  mkdirSync(OUT_DIR, { recursive: true })
  const built = []
  const failures = []
  // Id que não casa com nada é erro de digitação, não "nenhum pack pedido".
  if (wanted.length && !packs.length) {
    console.error(`nenhum pack com id ${wanted.join(', ')}`)
    process.exit(1)
  }
  for (const pack of packs) {
    try {
      if (downloadOnly) { extract(pack, download(pack)); continue }
      built.push(build(pack))
    } catch (error) {
      failures.push(pack.id)
      console.error(`  ✖ ${pack.id}: ${String(error.message).split('\n')[0]}`)
    }
  }
  if (!downloadOnly) {
    // O índice sai do DISCO, não do que rodou agora. Escrevê-lo a partir de
    // `built` significava que `fetch-samples.mjs guitar-clean` apagava os
    // outros seis do índice do app — e um id digitado errado apagava todos,
    // com código de saída zero.
    const onDisk = readdirSync(OUT_DIR, { withFileTypes: true })
      .filter((e) => e.isDirectory() && existsSync(join(OUT_DIR, e.name, 'manifest.json')))
      .map((e) => e.name)
      .sort()
    writeFileSync(join(OUT_DIR, 'packs.json'), `${JSON.stringify({ packs: onDisk })}\n`)
    const total = walk(OUT_DIR).reduce((sum, f) => sum + statSync(f).size, 0)
    console.log(`\n${onDisk.length} packs no índice, ${mb(total)} no total → ${OUT_DIR}`)
    if (failures.length) {
      console.error(`\n${failures.length} pack(s) falharam: ${failures.join(', ')}`)
      process.exitCode = 1
    }
  }
}
