package com.levelhard.cadentia.kit.cordas

/**
 * Cordas — os instrumentos de corda, port do `phelipiii/cordas`
 * (`public/src/instruments.js`, r23) via `CordaInstrument.swift` (1.16).
 *
 * Quatro instrumentos, e as diferenças entre eles não são cosméticas: a viola
 * caipira tem dez cordas em cinco ordens (duas em uníssono), a guitarra passa
 * por um amplificador vivo em vez de uma caixa ressonante, e o baixo é um
 * braço de quatro cordas graves. Tudo isso muda o grafo de áudio, então mora
 * no modelo e não na tela.
 */
data class CordaTone(
    val id: String,
    /** O máximo que a corda pode soar, em segundos. */
    val decay: Double,
    /** Abertura do filtro do laço. Mais brilho guarda mais agudo por volta. */
    val bright: Double,
    /** Perda por ida e volta em DC. */
    val loss: Double,
    /** Perda extra que cresce com a frequência — os parciais agudos morrem primeiro. */
    val lossHi: Double,
    /** Comprimento da excitação, em períodos. */
    val excite: Double,
    /** Maciez da unha. Um dedo macio passa-baixa o ruído. */
    val pickSoft: Double,
    /** Onde a palheta cai, em fração da corda. Comanda o pente. */
    val pickPos: Double,
    /** Clique do ataque — a unha raspando, a palheta escapando. */
    val click: Double,
    /** Modulação de tensão. Só a guitarra (e o baixo) têm; é o aço do ataque. */
    val tension: Double,
)

/** Uma corda do instrumento. `course` agrupa as dobradas da viola caipira. */
data class CordaStringSpec(
    val midi: Int,
    val course: Int,
    /** 1 = a mais grossa. Comanda a largura desenhada e o nível na mistura. */
    val gauge: Double,
)

/** As medidas do instrumento real, para a tela dizer honestamente quanto mente. Milímetros. */
data class CordaRealMeasures(
    /** Comprimento vibrante, pestana ao cavalete. */
    val scale: Double,
    /** Da primeira à última corda, na pestana. */
    val atNut: Double,
    /** A mesma distância no cavalete — onde a mão direita toca. */
    val atBridge: Double,
    val nameKey: String,
)

data class CordaInstrument(
    val id: String,
    val nameKey: String,
    val bus: Bus,
    val frets: Int,
    val strings: List<CordaStringSpec>,
    val tone: CordaTone,
    val real: CordaRealMeasures,
    /** Só quando a afinação tem nome que valha mostrar (cebolão em Ré). */
    val tuningNameKey: String? = null,
    /**
     * Quanto as cordas são mais grossas que as de um violão, na tela. `gauge`
     * é RELATIVO dentro do instrumento; a mi de um baixo tem 0,105 polegada
     * contra 0,046 da mi de um violão, e sem isto o baixo tinha "cara de violão".
     */
    val stringScale: Double = 1.0,
    /**
     * O corpo que a tela desenha. NÃO deriva de `bus`: o barramento diz por
     * onde o SOM passa, e o baixo está no acústico de propósito (ver `baixo`).
     */
    val bodyStyle: Body = Body.Box,
) {
    enum class Bus {
        /** Nylon e aço: a corda alimenta uma caixa ressonante. */
        Acoustic,
        /** A caixa não existe; um pré, um drive e um gabinete existem. */
        Electric,
    }

    enum class Body {
        /** Caixa de madeira com boca. */
        Box,
        /** Corpo maciço com captadores. */
        Solid,
    }

    /**
     * Se acorde é linguagem deste instrumento. Não é: um baixo toca UMA nota de
     * cada vez; a forma de acorde num braço de quatro cordas graves emudece
     * três delas para sobrar a fundamental, que é o oposto de tocar.
     */
    val playsChords: Boolean get() = id != "baixo"

    val stringCount: Int get() = strings.size
    val courseCount: Int get() = (strings.maxOfOrNull { it.course } ?: -1) + 1
    val isElectric: Boolean get() = bus == Bus.Electric

    /**
     * Qual banco de sample responde por este instrumento, quando a família
     * estiver ligada. A viola caipira cai no nylon porque não existe pack livre
     * de viola — e nylon erra menos que uma guitarra elétrica.
     */
    val sampleVoice: String
        get() = when (id) {
            "guitarra" -> "guitar-clean"
            "baixo" -> "bass-fingered"
            else -> "guitar-nylon"
        }

    companion object {
        private fun sixStrings(): List<CordaStringSpec> =
            listOf(40, 45, 50, 55, 59, 64).mapIndexed { i, midi -> CordaStringSpec(midi, i, 1 - i / 6.0) }

        val violao = CordaInstrument(
            id = "violao", nameKey = "cadentia.cordas.instrument.violao", bus = Bus.Acoustic, frets = 17,
            strings = sixStrings(),
            tone = CordaTone(
                id = "nyl", decay = 4.4, bright = 0.30, loss = 0.9973, lossHi = 0.0050,
                excite = 0.55, pickSoft = 0.62, pickPos = 0.14, click = 0.10, tension = 0.0,
            ),
            real = CordaRealMeasures(scale = 650.0, atNut = 43.0, atBridge = 58.0, nameKey = "cadentia.cordas.real.violao"),
        )

        val guitarra = CordaInstrument(
            id = "guitarra", nameKey = "cadentia.cordas.instrument.guitarra", bus = Bus.Electric, frets = 19,
            strings = sixStrings(),
            tone = CordaTone(
                id = "elec", decay = 4.4, bright = 0.46, loss = 0.9982, lossHi = 0.0028,
                excite = 0.35, pickSoft = 0.24, pickPos = 0.10, click = 0.34, tension = 0.06,
            ),
            real = CordaRealMeasures(scale = 648.0, atNut = 35.0, atBridge = 52.0, nameKey = "cadentia.cordas.real.guitarra"),
            bodyStyle = Body.Solid,
        )

        /**
         * Cinco ordens, dez cordas, cebolão em Ré. A quinta ordem fica em cima e
         * é a mais grave — o contrário de um violão.
         */
        val viola = CordaInstrument(
            id = "viola", nameKey = "cadentia.cordas.instrument.viola", bus = Bus.Acoustic, frets = 15,
            strings = listOf(
                CordaStringSpec(45, 0, 1.00), CordaStringSpec(57, 0, 0.55),
                CordaStringSpec(50, 1, 0.90), CordaStringSpec(62, 1, 0.50),
                CordaStringSpec(54, 2, 0.80), CordaStringSpec(66, 2, 0.45),
                CordaStringSpec(57, 3, 0.55), CordaStringSpec(57, 3, 0.50),
                CordaStringSpec(62, 4, 0.40), CordaStringSpec(62, 4, 0.38),
            ),
            tone = CordaTone(
                id = "vio", decay = 3.8, bright = 0.40, loss = 0.9966, lossHi = 0.0056,
                excite = 0.42, pickSoft = 0.40, pickPos = 0.18, click = 0.20, tension = 0.0,
            ),
            real = CordaRealMeasures(scale = 590.0, atNut = 42.0, atBridge = 56.0, nameKey = "cadentia.cordas.real.viola"),
            tuningNameKey = "cadentia.cordas.tuning.cebolao",
        )

        /**
         * Quatro cordas, mi grave em MIDI 28. `.Acoustic` apesar de elétrico: o
         * barramento separa QUEM PASSA pelo drive e pelo gabinete de guitarra, e
         * o gabinete é um passa-baixa em 4200 Hz desenhado para guitarra.
         * `loss` 0,9920 sai de varrer o parâmetro e medir (−18 dB no buffer,
         * contra −14,5 do violão); `decay` 3,2 é o teto real.
         */
        val baixo = CordaInstrument(
            id = "baixo", nameKey = "cadentia.cordas.instrument.baixo", bus = Bus.Acoustic, frets = 20,
            strings = listOf(28, 33, 38, 43).mapIndexed { i, midi -> CordaStringSpec(midi, i, 1 - i / 4.0) },
            tone = CordaTone(
                id = "bax", decay = 3.2, bright = 0.16, loss = 0.9920, lossHi = 0.0060,
                excite = 0.30, pickSoft = 0.70, pickPos = 0.12, click = 0.18, tension = 0.11,
            ),
            real = CordaRealMeasures(scale = 864.0, atNut = 29.0, atBridge = 57.0, nameKey = "cadentia.cordas.real.baixo"),
            stringScale = 1.9,
            bodyStyle = Body.Solid,
        )

        val all: List<CordaInstrument> = listOf(violao, guitarra, viola, baixo)

        fun named(id: String): CordaInstrument = all.firstOrNull { it.id == id } ?: violao
    }
}
