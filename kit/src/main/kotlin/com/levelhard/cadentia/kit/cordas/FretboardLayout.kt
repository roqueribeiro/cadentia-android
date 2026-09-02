package com.levelhard.cadentia.kit.cordas

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/**
 * O braço desenhado na tela, e a conta honesta de quanto ele mente — port do
 * `FretboardLayout.swift` (1.16).
 *
 * Num telefone de 390 pt o espaçamento das cordas cai em ~11,5 mm, que é
 * exatamente um violão clássico NO CAVALETE — e o cavalete é onde a mão direita
 * toca. O braço é o que se comprime: cinco casas de um violão real são 162 mm.
 * Por isso o número de casas visíveis é ajustável, e o painel mostra a escala
 * equivalente mudando ao vivo.
 */
class FretboardLayout(
    val size: Size,
    val instrument: CordaInstrument,
    visibleFrets: Int = 5,
    shift: Int = 0,
    pixelsPerMillimetre: Double = 5.46,
    spreadFactor: Double = 1.0,
    val hasRail: Boolean = true,
    padCount: Int = 0,
    handsFree: Boolean = false,
) {
    /** Quantas casas cabem no braço. Menos casas, escala mais realista; mais casas, mais alcance. */
    val visibleFrets: Int
    /** Primeira casa mostrada no topo do braço. */
    val shift: Int = maxOf(0, shift)
    /** Pixels por milímetro, calibrados contra o lado curto de um cartão. */
    val pixelsPerMillimetre: Double = maxOf(1.0, pixelsPerMillimetre)
    /** 0,85…1,25. Mãos maiores abrem as cordas para as bordas. */
    val spreadFactor: Double = spreadFactor.coerceIn(0.85, 1.25)
    /** A mão direita não toca: a faixa da batida sai e o braço fica com ela. Só no braço com casas. */
    val handsFree: Boolean = handsFree && padCount == 0
    val padCount: Int = maxOf(0, padCount)
    val padColumns: Int
    val padRows: Int
    /** Onde o braço termina e a área de palhetada começa. */
    val neckHeight: Double
    /** Posição horizontal de cada corda. */
    val stringX: List<Double>
    /** Posição vertical de cada traste, índice 0 sendo a pestana. */
    val fretY: List<Double>

    init {
        val width = size.width
        val height = size.height
        val count = instrument.stringCount

        if (this.padCount > 0) {
            // Os pads ganham um orçamento, escolhem as colunas que cabem nele e
            // tomam só as linhas de que precisam.
            val budget = height * 0.46
            val columns = padColumns(this.padCount, budget)
            val rows = maxOf(1, ceil(this.padCount.toDouble() / columns).toInt())
            padColumns = columns
            padRows = rows
            neckHeight = Math.round(minOf(budget, rows * 58.0 + 10)).toDouble()
        } else {
            padColumns = 0
            padRows = 0
            // Sem mão direita ninguém bate na metade de baixo: o braço desce até
            // o cavalete e as casas ganham quase o dobro de altura. O cavalete
            // FICA: é onde mora o nome da nota que cada corda toca agora.
            neckHeight = if (this.handsFree) Math.round(height - BRIDGE_HEIGHT).toDouble() else Math.round(height * 0.56).toDouble()
        }

        // O TETO DE CASAS SAI DA ALTURA: sem a mão direita cabem mais casas
        // porque o braço ocupa a tela inteira, e a última casa é quem decide
        // (ver fretBudget). Com a faixa da batida o teto continua 8.
        val fretSpan = neckHeight - (if (this.handsFree) MINIMUM_FRET_HEIGHT else 0.0)
        this.visibleFrets = maxOf(3, minOf(if (this.handsFree) fretBudget(fretSpan) else 8, visibleFrets))

        // Margens generosas de propósito: a primeira e a sexta corda coladas na
        // borda eram quase impossíveis de pegar com a mão inteira. O agudo ganha
        // mais espaço que o grave, porque é o que se toca sozinho mais vezes.
        val minLeft = 26.0
        val minRight = 40.0
        val rail = if (hasRail) RAIL_WIDTH else 0.0
        val base = width - (rail + width * 0.085) - width * 0.12
        val span = minOf(base * this.spreadFactor, width - rail - minLeft - minRight)
        val slack = width - rail - span - minLeft - minRight
        val left = rail + minLeft + slack * 0.35

        val xs = DoubleArray(count)
        if (instrument.courseCount < count) {
            // Viola caipira: as duas cordas de uma ordem ficam quase encostadas.
            val courseWidth = span / (instrument.courseCount - 1)
            for (i in 0 until count) {
                val course = instrument.strings[i].course.toDouble()
                val sub = (if (i % 2 == 0) -1.0 else 1.0) * minOf(9.0, courseWidth * 0.11)
                xs[i] = left + course * courseWidth + sub
            }
        } else {
            for (i in 0 until count) xs[i] = left + span * i / (count - 1)
        }
        stringX = xs.toList()

        // Trastes no espaçamento real da escala, para o braço parecer um braço.
        fun position(k: Int): Double = 1 - 2.0.pow(-k / 12.0)
        val firstFret = this.shift
        val visible = this.visibleFrets
        val top = position(firstFret)
        val bottom = position(firstFret + visible)
        // As casas começam DEPOIS da faixa das cordas soltas, quando ela existe.
        val openBand = if (this.handsFree) MINIMUM_FRET_HEIGHT else 0.0
        val usable = neckHeight - openBand
        fretY = (0..visible).map { k -> openBand + (position(firstFret + k) - top) / (bottom - top) * usable }
    }

    /**
     * A faixa das CORDAS SOLTAS, no topo do braço. Zero quando não existe.
     * Sem mão direita, a única forma de tirar som é encostar numa casa — e a
     * primeira faixa do braço era a casa 1: corda solta ficava impossível.
     * Vale um alvo de toque inteiro.
     */
    val openBandHeight: Double get() = if (handsFree) MINIMUM_FRET_HEIGHT else 0.0

    /** A altura que as CASAS dividem entre si — o braço menos a faixa das soltas. */
    val fretSpanHeight: Double get() = neckHeight - openBandHeight

    // ── hit testing ────────────────────────────────────────────────────────

    fun fretAt(y: Double): Int {
        // A faixa do topo é CORDA SOLTA — casa zero de verdade, não `shift`.
        if (openBandHeight > 0 && y < openBandHeight) return 0
        if (y <= 0) return shift
        for (k in 1..visibleFrets) if (y <= fretY[k]) return shift + k
        return shift + visibleFrets
    }

    fun stringAt(x: Double): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in stringX.indices) {
            val d = abs(stringX[i] - x)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** Metade do vão entre cordas — a faixa que pertence a cada uma. */
    val laneRadius: Double
        get() {
            if (stringX.size <= 1) return 40.0
            return abs(stringX[stringX.size - 1] - stringX[0]) / (stringX.size - 1) * 0.62
        }

    val laneHalfGap: Double
        get() {
            if (stringX.size <= 1) return 30.0
            return abs(stringX[stringX.size - 1] - stringX[0]) / (stringX.size - 1) * 0.5
        }

    // ── o trilho ───────────────────────────────────────────────────────────

    /** O que o trilho ocupa nesta tela — zero quando está desligado. */
    val railWidth: Double get() = if (hasRail) RAIL_WIDTH else 0.0

    val railMarks: List<Int>
        get() {
            val maximum = maxOf(0, instrument.frets - visibleFrets)
            return listOf(0, 3, 5, 7, 9, 12).filter { it <= maximum }
        }

    fun railY(index: Int, count: Int): Double =
        size.height * 0.10 + size.height * 0.78 * (if (count > 1) index.toDouble() / (count - 1) else 0.0)

    // ── o cavalete ─────────────────────────────────────────────────────────

    /** Onde a área de batida termina. Tudo abaixo pertence ao cavalete. */
    val strumBottom: Double
        get() {
            if (handsFree) return neckHeight
            return maxOf(neckHeight + 40, size.height - BRIDGE_HEIGHT)
        }

    val bridgeRect: Rect get() = Rect(0.0, strumBottom, size.width, size.height - strumBottom)

    // ── os pads de acorde ──────────────────────────────────────────────────

    /** A grade dos pads, calculada UMA vez e lida pelo desenho e pelo toque. */
    data class PadGrid(val columns: Int, val rows: Int, val count: Int, val area: Rect, val gap: Double) {
        fun rect(index: Int): Rect? {
            if (index < 0 || index >= count) return null
            val row = index / columns
            val column = index % columns
            val width = area.width / columns
            val height = area.height / rows
            // Uma última linha curta fica CENTRADA em vez de deixar um buraco à direita.
            val inThisRow = minOf(columns, count - row * columns)
            val indent = (columns - inThisRow) * width / 2
            return Rect(
                area.minX + indent + column * width + gap,
                area.minY + row * height + gap,
                width - gap * 2,
                height - gap * 2,
            )
        }

        fun index(point: Point): Int? {
            for (index in 0 until count) if (rect(index)?.contains(point) == true) return index
            return null
        }
    }

    val padGrid: PadGrid
        get() = PadGrid(
            columns = maxOf(1, padColumns), rows = maxOf(1, padRows), count = padCount,
            area = Rect(railWidth, 0.0, size.width - railWidth, neckHeight),
            gap = 5.0,
        )

    // ── as medidas honestas ────────────────────────────────────────────────

    data class Measurements(
        /** Milímetros entre cordas na tela. */
        val spacing: Double,
        val bundleWidth: Double,
        val neckLength: Double,
        /** Que comprimento de escala este braço implicaria num instrumento real. */
        val equivalentScale: Double,
        val frets: Int,
        val real: CordaRealMeasures,
    )

    val measurements: Measurements
        get() {
            val count = stringX.size
            val step = if (instrument.courseCount < count) {
                (stringX[count - 1] - stringX[0]) / (instrument.courseCount - 1)
            } else {
                (stringX[count - 1] - stringX[0]) / (count - 1)
            }
            val mm = { px: Double -> px / pixelsPerMillimetre }
            val neckMillimetres = mm(neckHeight)
            return Measurements(
                spacing = mm(step),
                bundleWidth = mm(stringX[count - 1] - stringX[0]),
                neckLength = neckMillimetres,
                equivalentScale = neckMillimetres / (1 - 2.0.pow(-visibleFrets / 12.0)),
                frets = visibleFrets,
                real = instrument.real,
            )
        }

    override fun equals(other: Any?): Boolean =
        other is FretboardLayout && other.size == size && other.instrument.id == instrument.id &&
            other.visibleFrets == visibleFrets && other.shift == shift &&
            other.pixelsPerMillimetre == pixelsPerMillimetre && other.spreadFactor == spreadFactor &&
            other.hasRail == hasRail && other.padCount == padCount && other.handsFree == handsFree

    override fun hashCode(): Int = listOf(size, instrument.id, visibleFrets, shift, padCount, handsFree, hasRail).hashCode()

    companion object {
        /** O trilho na borda esquerda. */
        const val RAIL_WIDTH: Double = 30.0

        /**
         * A faixa de baixo que pertence ao CAVALETE, não à batida. Existe pela
         * barra de abas: uma batida baixa caía nas abas e tirava a pessoa do
         * instrumento no meio da música. Um violão de verdade tem isso: não se
         * bate depois da pestana do cavalete.
         */
        const val BRIDGE_HEIGHT: Double = 38.0

        /** O menor alvo de toque: uma casa menor que isto existe na tela e não no dedo. */
        const val MINIMUM_FRET_HEIGHT: Double = 44.0

        /**
         * Quantas casas cabem num braço de `height` pontos sem que a mais
         * apertada fique menor que o dedo. As casas encolhem subindo o braço, e
         * quem manda é a ÚLTIMA.
         */
        fun fretBudget(height: Double, minimum: Double = MINIMUM_FRET_HEIGHT): Int {
            var best = 3
            for (count in 3..20) {
                val span = 1 - 2.0.pow(-count / 12.0)
                val last = 2.0.pow(-(count - 1) / 12.0) - 2.0.pow(-count / 12.0)
                if (!(span > 0 && height * last / span >= minimum)) break
                best = count
            }
            return best
        }

        /**
         * Três colunas, a não ser que duas dividam certinho e três não — e
         * quatro quando a tela é curta demais para as linhas que três pediriam.
         * Um pad mais baixo que ~52 pt não é alvo, é desafio.
         */
        fun padColumns(count: Int, availableHeight: Double = Double.POSITIVE_INFINITY): Int {
            if (count <= 0) return 1
            if (count <= 4) return maxOf(1, minOf(2, count))
            val minimumRow = 52.0
            fun rows(columns: Int): Int = maxOf(1, ceil(count.toDouble() / columns).toInt())
            fun waste(columns: Int): Int = (columns - count % columns) % columns
            val candidates = listOf(3, 2, 4)
            val fitting = candidates.filter { rows(it) * minimumRow <= availableHeight }
            if (fitting.isEmpty()) {
                // Nada cabe: a forma com menos linhas, e os pads ficam baixos.
                return candidates.minByOrNull { rows(it) } ?: 3
            }
            return fitting.minWithOrNull(compareBy({ waste(it) }, { abs(it - 3) })) ?: 3
        }
    }
}

/**
 * Mover a forma pelo braço — port do `ChordTranspose`.
 *
 * Com um acorde segurado, a pessoa quer levar o ACORDE para baixo, não mudar
 * de assunto. A posição carrega a forma junto — trilho e arrasto de dois dedos
 * transpõem o que está pressionado, mantendo os intervalos, e as notas que já
 * soam deslizam para o tom novo em vez de tocar de novo.
 */
object ChordTranspose {
    /** Quais cordas estão pressionadas acima da pestana. */
    fun fretted(frets: List<Int>): List<Int> = frets.indices.filter { frets[it] > 0 }

    /**
     * Desliza a forma inteira, presa para nunca atravessar a pestana. Devolve
     * quanto ela andou de verdade, e a forma nova.
     */
    fun transpose(frets: MutableList<Int>, delta: Int, maxFret: Int): Int {
        if (delta == 0) return 0
        val indices = fretted(frets)
        if (indices.isEmpty()) return 0
        val low = indices.minOf { frets[it] }
        val high = indices.maxOf { frets[it] }
        val d = maxOf(1 - low, minOf(maxFret - high, delta))
        if (d == 0) return 0
        for (i in indices) frets[i] += d
        return d
    }

    /** A janela de casas persegue a forma sozinha, para a mão nunca sair da tela. */
    fun windowFollowing(frets: List<Int>, shift: Int, visibleFrets: Int, maxFret: Int): Int {
        val indices = fretted(frets)
        if (indices.isEmpty()) return shift
        val low = indices.minOf { frets[it] }
        val high = indices.maxOf { frets[it] }
        var result = shift
        if (high > result + visibleFrets) result = high - visibleFrets
        if (low < result + 1) result = low - 1
        return maxOf(0, minOf(maxOf(0, maxFret - visibleFrets), result))
    }

    /** Pula direto para uma posição, levando a forma junto. */
    fun jump(target: Int, frets: MutableList<Int>, visibleFrets: Int, maxFret: Int): Int {
        val position = maxOf(0, minOf(maxOf(0, maxFret - visibleFrets), target))
        val indices = fretted(frets)
        if (indices.isNotEmpty()) {
            val low = indices.minOf { frets[it] }
            transpose(frets, (position + 1) - low, maxFret)
        }
        return position
    }
}
