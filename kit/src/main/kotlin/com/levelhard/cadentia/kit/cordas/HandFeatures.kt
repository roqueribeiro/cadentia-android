package com.levelhard.cadentia.kit.cordas

/**
 * As 21 juntas da mão, com nome — port do `HandFeatures.swift`.
 *
 * O MediaPipe indexa 0…20 e o Vision da Apple nomeia; a topologia é a mesma e
 * o mapeamento é um para um. Nada neste pacote indexa um vetor de pontos por
 * número mágico — o adaptador de cada plataforma preenche um `HandLandmarks` e
 * a geometria só pergunta por `IndexTip`.
 */
enum class HandJoint(val index: Int) {
    Wrist(0),
    ThumbCMC(1), ThumbMCP(2), ThumbIP(3), ThumbTip(4),
    IndexMCP(5), IndexPIP(6), IndexDIP(7), IndexTip(8),
    MiddleMCP(9), MiddlePIP(10), MiddleDIP(11), MiddleTip(12),
    RingMCP(13), RingPIP(14), RingDIP(15), RingTip(16),
    LittleMCP(17), LittlePIP(18), LittleDIP(19), LittleTip(20),
}

/**
 * Qual mão é esta, do ponto de vista do rastreador — já corrigida do
 * espelhamento pelo adaptador. É a única IDENTIDADE estável entre quadros, e é
 * o que separa um destro de um canhoto: a mão do braço é a esquerda, ou não é.
 */
enum class HandChirality {
    Left, Right, Unknown;

    val opposite: HandChirality
        get() = when (this) {
            Left -> Right
            Right -> Left
            Unknown -> Unknown
        }
}

/** Quem segura o braço. `Auto` descobre nos primeiros quadros e depois para de adivinhar. */
enum class PlayerHandedness(val id: String) {
    Auto("auto"), Right("right"), Left("left");

    /** A mão que aperta as cordas. Destro aperta com a esquerda; canhoto, com a direita. */
    val neckChirality: HandChirality
        get() = when (this) {
            Right -> HandChirality.Left
            Left -> HandChirality.Right
            Auto -> HandChirality.Unknown
        }
}

/**
 * Uma mão, em coordenadas da VIEW — já desnormalizada, já espelhada se a
 * câmera frontal estiver em uso, já com o Y virado se a fonte precisou. Fazer
 * essa conversão no adaptador e não aqui é deliberado: errar não dá erro, o
 * violão só aparece de cabeça para baixo e a batida para baixo vira para cima.
 */
data class HandLandmarks(val points: List<Point>, val chirality: HandChirality = HandChirality.Unknown) {
    init {
        require(points.size == 21) { "uma mão tem 21 juntas, não ${points.size}" }
    }

    operator fun get(joint: HandJoint): Point = points[joint.index]

    companion object {
        fun of(points: List<Point>, chirality: HandChirality = HandChirality.Unknown): HandLandmarks? =
            if (points.size == 21) HandLandmarks(points, chirality) else null
    }
}

/** Medidas puras de uma mão. Sem estado, sem tempo, sem suavização — isso mora em `AirGuitarGeometry`. */
object HandFeatures {
    /**
     * Tamanho aparente da mão. É a melhor pista de distância que uma câmera
     * só dá: o vão ENTRE as mãos muda quando a pessoa só mexe os braços, e a
     * palma não muda de tamanho.
     */
    fun handSize(hand: HandLandmarks): Double =
        (distance(hand[HandJoint.MiddleMCP], hand[HandJoint.Wrist]) +
            distance(hand[HandJoint.IndexMCP], hand[HandJoint.LittleMCP])) / 2

    /**
     * Centro da palma: pulso, base do indicador, base do mindinho. A ÂNCORA é a
     * palma e não uma ponta de dedo: a ponta se mexe quando a mão abre e fecha.
     */
    fun palm(hand: HandLandmarks): Point {
        val a = hand[HandJoint.Wrist]
        val b = hand[HandJoint.IndexMCP]
        val c = hand[HandJoint.LittleMCP]
        return Point((a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3)
    }

    /**
     * Quais dedos estão estendidos, como um desenho de 4 bits da mão.
     * Estendido é a ponta mais longe do pulso do que a junta do meio, por uma
     * margem clara — um dedo dobrado nunca pode ler como aberto.
     */
    fun shapeMask(hand: HandLandmarks): Int {
        val wrist = hand[HandJoint.Wrist]
        val pairs = listOf(
            HandJoint.IndexTip to HandJoint.IndexPIP, HandJoint.MiddleTip to HandJoint.MiddlePIP,
            HandJoint.RingTip to HandJoint.RingPIP, HandJoint.LittleTip to HandJoint.LittlePIP,
        )
        var mask = 0
        for ((offset, pair) in pairs.withIndex()) {
            val tip = distance(hand[pair.first], wrist)
            val pip = distance(hand[pair.second], wrist)
            if (tip > pip * 1.32) mask = mask or (1 shl offset)
        }
        return mask
    }

    /**
     * Quão separadas estão as pontas dos dedos, em unidades de palma. É isto —
     * e não "quantos dedos estão estendidos" — que separa os gestos como uma
     * mão tocando os faz: pontas juntas é batida rápida, palma aberta é o tchac.
     */
    fun spread(hand: HandLandmarks): Double {
        val scale = handSize(hand)
        if (scale <= 0) return 0.0
        val tips = listOf(hand[HandJoint.IndexTip], hand[HandJoint.MiddleTip], hand[HandJoint.RingTip], hand[HandJoint.LittleTip])
        var sum = 0.0
        var count = 0.0
        for (a in tips.indices) {
            for (b in a + 1 until tips.size) {
                sum += distance(tips[a], tips[b])
                count += 1
            }
        }
        if (count <= 0) return 0.0
        return (sum / count) / scale
    }

    /** Polegar e indicador encostados — o gesto de PINÇAR a corda. 0,34 da palma, não 0,55. */
    fun isPinching(hand: HandLandmarks): Boolean {
        val scale = distance(hand[HandJoint.MiddleMCP], hand[HandJoint.Wrist])
        if (scale <= 0) return false
        return distance(hand[HandJoint.ThumbTip], hand[HandJoint.IndexTip]) < scale * 0.34
    }

    /** Um punho fechado de verdade — dedos recolhidos em relação ao tamanho da mão. */
    fun isFist(hand: HandLandmarks): Boolean {
        val wrist = hand[HandJoint.Wrist]
        val scale = distance(hand[HandJoint.MiddleMCP], wrist)
        if (scale <= 0) return false
        val tips = listOf(HandJoint.IndexTip, HandJoint.MiddleTip, HandJoint.RingTip, HandJoint.LittleTip)
        val mean = tips.sumOf { distance(hand[it], wrist) } / 4
        return mean / scale < 1.35
    }

    /** Polegar afastado da mão. */
    fun isThumbOut(hand: HandLandmarks): Boolean {
        val scale = handSize(hand)
        if (scale <= 0) return false
        return distance(hand[HandJoint.ThumbTip], hand[HandJoint.IndexMCP]) > scale * 1.05
    }
}

/**
 * O que a mão direita está fazendo. A POSTURA escolhe o modo; a velocidade só
 * executa dentro dele. Escolher o modo pela velocidade tremula na fronteira.
 */
enum class RightHandMode(val id: String) {
    /** O padrão. Qualquer mão sem gesto inconfundível toca como palheta. */
    Strum("strum"),
    /** Indicador apontando, anelar e mindinho recolhidos: cada ponta é um dedo. */
    Fingerstyle("fingerstyle"),
    /** Palma aberta: o tapa percussivo. */
    Tchac("tchac"),
    /** Punho bem fechado com o polegar afastado: corda por corda. */
    Thumb("thumb");

    companion object {
        fun read(hand: HandLandmarks): RightHandMode {
            val mask = HandFeatures.shapeMask(hand)
            val count = HandChordMapping.fingerCount(mask)
            val spread = HandFeatures.spread(hand)
            val onlyIndex = (mask and 0b0001) != 0 && (mask and 0b1100) == 0

            if (onlyIndex) return Fingerstyle
            if (spread > 0.45 && count >= 2) return Tchac
            if (HandFeatures.isThumbOut(hand) && count == 0 && spread < 0.20) return Thumb
            return Strum
        }
    }
}
