package com.levelhard.cadentia.kit.cordas

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * O violão que aparece entre as suas mãos — port do `AirGuitarGeometry.swift`
 * (1.16), com toda a histerese em TEMPO (segundos), não em quadros: é o que
 * sobrevive a uma mudança de taxa de quadros entre o Vision, o MediaPipe e o
 * navegador.
 *
 * O que uma câmera frontal só resolve, e o que não resolve:
 * - **resolve**: ONDE a mão está ao longo do braço imaginário, e o instante em
 *   que a mão direita cruza o plano das cordas, com velocidade e sentido;
 * - **não resolve**: EM QUAL corda um dedo está. Seis cordas ocupam ~40 px na
 *   imagem, ~7 px cada — a mesma ordem de grandeza do ruído do rastreamento.
 */
class AirGuitarGeometry {
    enum class Calibration(val id: String) { Aiming("aiming"), Ready("ready"), Lost("lost") }

    data class Pluck(
        val string: Int,
        val velocity: Double,
        /** Segundos a partir de agora: cada corda ganha o instante em que foi cruzada de verdade. */
        val delay: Double,
    )

    sealed class ChordIntent {
        object Unchanged : ChordIntent()
        data class Chord(val name: String) : ChordIntent()
        /** Solo por pinça: uma corda, uma casa. */
        data class Solo(val fret: Int) : ChordIntent()
        object ReleaseSolo : ChordIntent()
    }

    class Frame {
        var calibration: Calibration = Calibration.Aiming
        var progress: Double = 0.0
        var plucks: List<Pluck> = emptyList()
        var tchacVelocity: Double? = null
        var dampAll = false
        var rawMask = 0
        var confirmedMask = 0
        var mode: RightHandMode = RightHandMode.Strum
        var soloFret: Int? = null
        var didRecalibrate = false
        var body: Point? = null
        var axis = Vector(0.0, -1.0)
        var length = 0.0
        var posture: AirGuitarPosture = AirGuitarPosture.standing
        /** Quem segura o braço, como resolvido neste quadro. `Auto` = pergunta ainda sem resposta. */
        var handedness: PlayerHandedness = PlayerHandedness.Auto
        /** Por que a pose não está sendo aceita, se não está. */
        var poseHint: PoseHint = PoseHint.None
        var pickU: Double? = null
        var neckPosition = 0.0
        var spread = 0.0
        var pickHandNearEdge = false
        /** As duas mãos como a geometria as entendeu, para a tela DESENHAR o que está lendo. */
        var neckHand: HandLandmarks? = null
        var pickHand: HandLandmarks? = null
    }

    // ── estado ─────────────────────────────────────────────────────────────

    var calibration: Calibration = Calibration.Aiming
        private set
    var posture: AirGuitarPosture = AirGuitarPosture.standing
        private set
    /** O que a pessoa declarou. `Auto` deixa a geometria descobrir. */
    var handedness: PlayerHandedness = PlayerHandedness.Auto
        private set
    var poseHint: PoseHint = PoseHint.None
        private set
    private var learnedNeckChirality: HandChirality = HandChirality.Unknown
    private var handednessVotes = 0
    var body: Point? = null
        private set
    var axis = Vector(0.0, -1.0)
        private set
    var length = 0.0
        private set

    private var baseLength = 0.0
    private var baseHandSize = 1.0
    private var acrossOffset = 0.0
    private var scale = 1.0

    private class Sample(val body: Point, val pick: Point, val tip: Point, val handSize: Double)
    private val samples = ArrayList<Sample>()
    private var stillFor = 0.0
    private var openTogetherFor = 0.0

    private var lastTime: Double? = null
    private var lastPickPalm: Point? = null
    private var lastNeckPalm: Point? = null
    private var pickSpeed = 0.0
    private var neckSpeed = 0.0
    private var missingSince: Double? = null

    private var palmU: Double? = null
    private var armed = BooleanArray(0)
    private var lastHitAt = DoubleArray(0)
    private val tipU = HashMap<HandJoint, Double>()
    private val tipSeenAt = HashMap<HandJoint, Double>()
    private var lastTchacAt = -1e9

    private var pendingMask = -1
    private var maskSince = 0.0
    private var confirmedMask = -1
    private var pendingMode: RightHandMode = RightHandMode.Strum
    private var modeSince = 0.0
    private var mode: RightHandMode = RightHandMode.Strum
    private var pendingPinch = false
    private var pinchSince = 0.0
    private var soloActive = false

    private var stringCount = 6
    private var playable = BooleanArray(0)
    private val pendingPlucks = ArrayList<Pluck>()
    private var now = 0.0

    /** Definido pelo modelo a cada quadro: os acordes oferecidos mudam com a música. */
    var chordNames: List<String> = emptyList()

    // ── geometria ──────────────────────────────────────────────────────────

    private val perpendicular: Vector get() = Vector(-axis.dy, axis.dx)

    /**
     * Onde cada corda fica através do braço, em comprimentos de braço. Do ponto
     * de vista de quem toca, o grave fica EM CIMA: o índice 0 ganha o `u`
     * positivo.
     */
    fun stringU(index: Int): Double = ((stringCount - 1) / 2.0 - index) * posture.spacing

    private fun along(point: Point): Double {
        val b = body ?: return 0.0
        if (length <= 0) return 0.0
        return ((point.x - b.x) * axis.dx + (point.y - b.y) * axis.dy) / length
    }

    private fun across(point: Point): Double {
        val b = body ?: return 0.0
        if (length <= 0) return 0.0
        val p = perpendicular
        return ((point.x - b.x) * p.dx + (point.y - b.y) * p.dy) / length
    }

    /** Ponto no instrumento desenhado, dada a posição ao longo e através. A tela desenha tudo com isto. */
    fun point(along: Double, across: Double): Point {
        val b = body ?: return Point.zero
        val p = perpendicular
        return Point(
            b.x + axis.dx * (length * along) + p.dx * (length * across),
            b.y + axis.dy * (length * along) + p.dy * (length * across),
        )
    }

    fun reset() {
        poseHint = PoseHint.None
        learnedNeckChirality = HandChirality.Unknown
        handednessVotes = 0
        calibration = Calibration.Aiming
        body = null
        length = 0.0
        baseLength = 0.0
        scale = 1.0
        samples.clear()
        stillFor = 0.0
        openTogetherFor = 0.0
        lastPickPalm = null
        lastNeckPalm = null
        palmU = null
        armed = BooleanArray(0)
        lastHitAt = DoubleArray(0)
        tipU.clear()
        tipSeenAt.clear()
        pendingMask = -1
        confirmedMask = -1
        soloActive = false
        pendingMode = RightHandMode.Strum
        mode = RightHandMode.Strum
        pendingPinch = false
        missingSince = null
        lastTime = null
    }

    fun setPosture(new: AirGuitarPosture) {
        posture = new
    }

    fun setHandedness(new: PlayerHandedness) {
        handedness = new
        learnedNeckChirality = HandChirality.Unknown
        handednessVotes = 0
    }

    /** Qual mão deveria estar no braço. Vazio até a pergunta se resolver. */
    val resolvedNeckChirality: HandChirality
        get() {
            val declared = handedness.neckChirality
            if (declared != HandChirality.Unknown) return declared
            return if (handednessVotes >= 4) learnedNeckChirality else HandChirality.Unknown
        }

    /** O que o modo acredita sobre a pessoa, para a tela mostrar. */
    val resolvedHandedness: PlayerHandedness
        get() {
            if (handedness != PlayerHandedness.Auto) return handedness
            return when (resolvedNeckChirality) {
                HandChirality.Left -> PlayerHandedness.Right
                HandChirality.Right -> PlayerHandedness.Left
                HandChirality.Unknown -> PlayerHandedness.Auto
            }
        }

    /**
     * Aprende a resposta pela geometria, e depois para de adivinhar. Depois
     * disso a RESPOSTA comanda a atribuição — mais firme que geometria e a única
     * coisa que faz um canhoto funcionar.
     */
    private fun learnHandedness(hands: List<HandLandmarks>, neckIndex: Int) {
        if (handedness != PlayerHandedness.Auto || neckIndex < 0 || hands.size < 2) return
        val candidate = hands[neckIndex].chirality
        val other = hands[1 - neckIndex].chirality
        if (candidate == HandChirality.Unknown || other != candidate.opposite) return
        when {
            candidate == learnedNeckChirality -> handednessVotes = minOf(12, handednessVotes + 1)
            handednessVotes > 0 -> handednessVotes -= 1
            else -> {
                learnedNeckChirality = candidate
                handednessVotes = 1
            }
        }
    }

    private fun arm() {
        armed = BooleanArray(stringCount) { true }
        lastHitAt = DoubleArray(stringCount) { -1e9 }
    }

    // ── o quadro ───────────────────────────────────────────────────────────

    fun update(
        hands: List<HandLandmarks>,
        time: Double,
        instrument: CordaInstrument,
        viewSize: Size,
        applyChord: (ChordIntent) -> List<Boolean>,
    ): Frame {
        now = time
        stringCount = instrument.stringCount
        if (armed.size != stringCount) arm()

        val dt = (time - (lastTime ?: time)).coerceIn(0.001, 0.2)
        lastTime = time

        val frame = Frame()
        frame.posture = posture
        frame.handedness = resolvedHandedness
        frame.poseHint = poseHint
        frame.mode = mode
        frame.confirmedMask = maxOf(confirmedMask, 0)

        val calibrated = baseLength > 0 && body != null

        // Uma batida forte leva a mão para fora do quadro — é assim que tocar
        // parece. As duas mãos são necessárias para CALIBRAR; depois disso se
        // trabalha com o que estiver visível.
        if (hands.isEmpty() || (!calibrated && hands.size < 2)) {
            if (missingSince == null) missingSince = time
            val since = missingSince ?: time
            if (calibration == Calibration.Ready && time - since > 0.9) {
                calibration = Calibration.Lost
            } else if (calibration == Calibration.Aiming && time - since > 0.4) {
                samples.clear()
                stillFor = 0.0
            }
            // A mão sumiu, então o gesto acabou.
            palmU = null
            tipU.clear()
            frame.neckHand = hands.firstOrNull()
            frame.calibration = calibration
            frame.poseHint = poseHint
            frame.progress = minOf(1.0, stillFor / 0.9)
            frame.body = body
            frame.axis = axis
            frame.length = length
            return frame
        }
        missingSince = null
        if (calibration == Calibration.Lost) calibration = Calibration.Ready

        // ── quem é quem ────────────────────────────────────────────────────
        val palms = hands.map { HandFeatures.palm(it) }
        var neckIndex = -1
        var pickIndex = -1
        if (hands.size >= 2) {
            // Identidade primeiro: a quiralidade de uma mão não muda quando ela
            // se mexe, e a geometria muda.
            val wanted = resolvedNeckChirality
            val wantedIndex = hands.indexOfFirst { it.chirality == wanted }
            val b = body
            if (wanted != HandChirality.Unknown && wantedIndex >= 0 && hands.any { it.chirality == wanted.opposite }) {
                neckIndex = wantedIndex
            } else if (calibration == Calibration.Ready && b != null) {
                val headPoint = Point(b.x + axis.dx * length, b.y + axis.dy * length)
                val d0 = distance(palms[0], headPoint)
                val d1 = distance(palms[1], headPoint)
                neckIndex = if (d0 < d1) 0 else 1
            } else {
                neckIndex = if (palms[0].y < palms[1].y) 0 else 1 // a mão do braço fica mais alta
            }
            pickIndex = 1 - neckIndex
            learnHandedness(hands, neckIndex)
            frame.handedness = resolvedHandedness
        } else {
            val b = body
            if (b != null) {
                val headPoint = Point(b.x + axis.dx * length, b.y + axis.dy * length)
                if (distance(palms[0], headPoint) < distance(palms[0], b)) neckIndex = 0 else pickIndex = 0
            }
        }

        val neckHand = if (neckIndex >= 0) hands[neckIndex] else null
        val pickHand = if (pickIndex >= 0) hands[pickIndex] else null
        frame.neckHand = neckHand
        frame.pickHand = pickHand
        val neckPalm = if (neckIndex >= 0) palms[neckIndex] else null
        val pickPalm = if (pickIndex >= 0) palms[pickIndex] else null

        // Uma mão que acabou de voltar começa o gesto do zero.
        if (pickHand != null && lastPickPalm == null) {
            palmU = null
            tipU.clear()
        }

        pickSpeed = speed(lastPickPalm, pickPalm, dt)
        neckSpeed = speed(lastNeckPalm, neckPalm, dt)
        lastPickPalm = pickPalm
        lastNeckPalm = neckPalm

        // ── calibração ─────────────────────────────────────────────────────
        if (calibration == Calibration.Aiming) {
            if (neckHand == null || pickHand == null || neckPalm == null || pickPalm == null) {
                frame.calibration = calibration
                return frame
            }
            calibrate(neckHand, pickHand, neckPalm, pickPalm, dt, viewSize)
            frame.calibration = calibration
            frame.poseHint = poseHint
            frame.progress = minOf(1.0, stillFor / 0.9)
            if (calibration == Calibration.Ready) frame.didRecalibrate = true
            frame.body = body
            frame.axis = axis
            frame.length = length
            frame.posture = posture
            return frame
        }

        // Abrir AS DUAS mãos, juntas e paradas, diante da câmera é recalibrar.
        if (neckHand != null && pickHand != null && neckPalm != null && pickPalm != null) {
            val reference = if (length > 0) length else baseLength
            val bothOpen = HandFeatures.shapeMask(neckHand) == 0b1111 && HandFeatures.shapeMask(pickHand) == 0b1111
            val together = distance(neckPalm, pickPalm) < reference * 0.55
            val still = pickSpeed < reference * 0.30 && neckSpeed < reference * 0.30
            if (bothOpen && together && still) {
                openTogetherFor += dt
                if (openTogetherFor > 1.0) {
                    reset()
                    frame.didRecalibrate = true
                    frame.calibration = calibration
                    return frame
                }
            } else {
                openTogetherFor = 0.0
            }
        }

        track(neckPalm, pickPalm, dt, viewSize, neckHand, pickHand)

        // ── mão esquerda: qual acorde ──────────────────────────────────────
        var intent: ChordIntent = ChordIntent.Unchanged
        if (neckHand != null && neckPalm != null) {
            frame.neckPosition = along(neckPalm)
            intent = readLeftHand(neckHand, time, frame)
        }
        val playableList = applyChord(intent)
        playable = if (playableList.size == stringCount) playableList.toBooleanArray() else BooleanArray(stringCount) { true }
        (intent as? ChordIntent.Solo)?.let { frame.soloFret = it.fret }

        // ── mão direita: a palheta ─────────────────────────────────────────
        pendingPlucks.clear()
        if (pickHand != null && pickPalm != null) {
            readRightHand(pickHand, pickPalm, dt, time, frame)
            val margin = 34.0
            frame.pickHandNearEdge = pickPalm.x < margin || pickPalm.x > viewSize.width - margin ||
                pickPalm.y < margin || pickPalm.y > viewSize.height - margin
        } else {
            palmU = null
        }

        frame.plucks = pendingPlucks.toList()
        frame.calibration = calibration
        frame.body = body
        frame.axis = axis
        frame.length = length
        frame.posture = posture
        frame.mode = mode
        frame.confirmedMask = maxOf(confirmedMask, 0)
        frame.pickU = tipU[HandJoint.IndexTip]
        return frame
    }

    private fun speed(previous: Point?, current: Point?, dt: Double): Double {
        if (previous == null || current == null) return 0.0
        return distance(previous, current) / dt
    }

    // ── calibração ─────────────────────────────────────────────────────────

    private fun calibrate(
        neckHand: HandLandmarks, pickHand: HandLandmarks,
        neckPalm: Point, pickPalm: Point, dt: Double, viewSize: Size,
    ) {
        samples.add(Sample(neckPalm, pickPalm, pickHand[HandJoint.IndexTip], HandFeatures.handSize(neckHand)))
        if (samples.size > 45) samples.removeAt(0)

        // Contar quadros não basta: espera AS DUAS mãos pararem de verdade.
        val gap = distance(neckPalm, pickPalm)
        val still = pickSpeed < gap * 0.35 && neckSpeed < gap * 0.35
        stillFor = if (still) stillFor + dt else 0.0
        if (stillFor < 0.9 || samples.size < 12) return

        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
        val bx = median(samples.map { it.body.x })
        val by = median(samples.map { it.body.y })
        val px = median(samples.map { it.pick.x })
        val py = median(samples.map { it.pick.y })
        val vx = bx - px
        val vy = by - py
        val l = sqrt(vx * vx + vy * vy)
        if (l < viewSize.width * 0.12) {
            stillFor = 0.0
            return
        }

        // UM VIOLÃO NÃO SE SEGURA EM PÉ, nem deitado: uma pose que não é pose de
        // tocar é recusada em vez de ser tirada a média.
        if (abs(vx) / l < MINIMUM_TILT) {
            poseHint = PoseHint.TooUpright
            stillFor = 0.0
            return
        }
        if (abs(vy) / l < MINIMUM_DROP) {
            poseHint = PoseHint.TooFlat
            stillFor = 0.0
            return
        }
        poseHint = PoseHint.None

        axis = Vector(vx / l, vy / l)
        // Cuidado: o corpo fica onde a mão da PALHETA descansa.
        val anchor = Point(px, py)
        body = anchor
        baseLength = l
        length = maxOf(viewSize.height * 0.12, minOf(roomFor(posture, stringCount, axis, anchor, viewSize), l))
        scale = 1.0

        // Onde a ponta do indicador descansa, no eixo transversal: as cordas se
        // centram ali, porque é o dedo que vai tocá-las.
        val tx = median(samples.map { it.tip.x })
        val ty = median(samples.map { it.tip.y })
        val p = perpendicular
        acrossOffset = ((tx - px) * p.dx + (ty - py) * p.dy) / l

        baseHandSize = maxOf(1.0, median(samples.map { it.handSize }))
        // Mão grande no quadro é pessoa perto, que é pessoa sentada.
        posture = if (baseHandSize / viewSize.width > 0.13) AirGuitarPosture.seated else AirGuitarPosture.standing

        calibration = Calibration.Ready
        confirmedMask = -1
        pendingMask = -1
        arm()
    }

    // ── o corpo segue a pessoa, devagar ────────────────────────────────────

    private fun track(
        neckPalm: Point?, pickPalm: Point?, dt: Double, viewSize: Size,
        neckHand: HandLandmarks?, pickHand: HandLandmarks?,
    ) {
        var current = body ?: return
        val reference = if (length > 0) length else baseLength
        // A mão direita correndo enquanto a esquerda fica é uma batida em
        // andamento: o corpo não pode fugir.
        val playing = pickSpeed > reference * 0.5 && neckSpeed < reference * 0.25

        // Cada eixo é ancorado na mão que NÃO faz aquele gesto: AO LONGO vem da
        // mão da palheta, ATRAVÉS vem da mão do braço.
        val p = perpendicular
        val errAlong = pickPalm?.let { (it.x - current.x) * axis.dx + (it.y - current.y) * axis.dy } ?: 0.0
        val errAcross = neckPalm?.let { (it.x - current.x) * p.dx + (it.y - current.y) * p.dy } ?: 0.0
        val kAcross = 1 - exp(-dt / 0.4)
        val kAlong = 1 - exp(-dt / (if (playing) 4.0 else 0.4))
        current = Point(
            current.x + axis.dx * errAlong * kAlong + p.dx * errAcross * kAcross,
            current.y + axis.dy * errAlong * kAlong + p.dy * errAcross * kAcross,
        )
        body = current
        // NADA prende o corpo dentro da tela: o enquadramento se resolve com aviso, não com clamp.

        // A inclinação segue também, devagar: a mão esquerda desliza AO LONGO do braço.
        if (neckPalm != null && !playing) {
            val dx = neckPalm.x - current.x
            val dy = neckPalm.y - current.y
            val len = sqrt(dx * dx + dy * dy)
            if (len > reference * 0.35) {
                val k = 1 - exp(-dt / 2.0)
                var nx = axis.dx + (dx / len - axis.dx) * k
                var ny = axis.dy + (dy / len - axis.dy) * k
                val n = maxOf(1e-6, sqrt(nx * nx + ny * ny))
                nx /= n
                ny /= n
                axis = Vector(nx, ny)
            }
        }

        // Mais longe da câmera a mão encolhe na imagem e o violão encolhe junto. Suavizado.
        val sizes = ArrayList<Double>()
        if (neckHand != null) sizes.add(HandFeatures.handSize(neckHand))
        if (pickHand != null) sizes.add(HandFeatures.handSize(pickHand))
        val observed = if (sizes.isEmpty()) baseHandSize else sizes.sum() / sizes.size
        val target = (observed / baseHandSize).coerceIn(0.45, 2.4)
        scale += (target - scale) * (1 - exp(-dt / 0.35))

        // O TETO É O QUE CABE DE VERDADE, seguido devagar e assimetricamente:
        // sair da borda é urgente, voltar não é.
        val h = viewSize.height
        val wanted = maxOf(h * 0.16, baseLength * scale)
        val room = roomFor(posture, stringCount, axis, body, viewSize)
        val fitted = minOf(room, wanted)
        val tau = if (fitted < length) 0.12 else 0.65
        val eased = length + (fitted - length) * (1 - exp(-dt / tau))
        length = maxOf(h * 0.12, if (length > 0) eased else fitted)
    }

    // ── mão esquerda ───────────────────────────────────────────────────────

    private fun readLeftHand(hand: HandLandmarks, time: Double, frame: Frame): ChordIntent {
        // A pinça aperta a corda, e onde ela está é a casa. Precisa segurar.
        val pinchingNow = HandFeatures.isPinching(hand)
        if (pinchingNow != pendingPinch) {
            pendingPinch = pinchingNow
            pinchSince = time
        }
        val pinching = if (time - pinchSince >= PINCH_HOLD) pinchingNow else soloActive

        if (pinching) {
            val thumb = hand[HandJoint.ThumbTip]
            val index = hand[HandJoint.IndexTip]
            val mid = Point((thumb.x + index.x) / 2, (thumb.y + index.y) / 2)
            val s = along(mid)
            val s0 = posture.nut
            val s12 = posture.soundhole + 0.30
            val fret = ((s0 - s) / maxOf(1e-6, s0 - s12) * 12).roundToInt().coerceIn(0, 12)
            soloActive = true
            frame.soloFret = fret
            return ChordIntent.Solo(fret)
        }

        if (soloActive) {
            soloActive = false
            return ChordIntent.ReleaseSolo
        }

        val mask = HandFeatures.shapeMask(hand)
        frame.rawMask = mask
        if (mask != pendingMask) {
            pendingMask = mask
            maskSince = time
        }
        if (time - maskSince >= SHAPE_HOLD && mask != confirmedMask) {
            confirmedMask = mask
            frame.confirmedMask = mask
            currentChordName?.let { return ChordIntent.Chord(it) }
        }
        frame.confirmedMask = maxOf(confirmedMask, 0)
        // A mão ESQUERDA não emudece: segurar um braço imaginário deixa os dedos
        // curvados, e toda leitura geométrica de "punho" confunde as duas coisas.
        return ChordIntent.Unchanged
    }

    private val currentChordName: String?
        get() = HandChordMapping.chord(maxOf(confirmedMask, 0), chordNames)

    // ── mão direita ────────────────────────────────────────────────────────

    private fun readRightHand(hand: HandLandmarks, palm: Point, dt: Double, time: Double, frame: Frame) {
        val observed = RightHandMode.read(hand)
        if (observed != pendingMode) {
            pendingMode = observed
            modeSince = time
        }
        if (time - modeSince >= MODE_HOLD) mode = observed
        frame.mode = mode
        frame.spread = HandFeatures.spread(hand)

        // A palma é o que sobrevive ao borrão: a BATIDA é lida da palma; as
        // pontas só importam no dedilhado, que é lento e nítido.
        val u = across(palm)
        val a = along(palm)
        val near = a > -1.2 && a < 1.0
        var palmVelocity = 0.0
        val previousPalm = palmU
        if (previousPalm != null && near) palmVelocity = (u - previousPalm) / dt

        val inside = abs(u) < posture.spacing * (stringCount + 3) / 2 + 0.12

        // O NÚCLEO: há cordas paradas no espaço e uma mão passando por elas.
        // TODA passada soa, em qualquer velocidade, para cima ou para baixo.
        if (mode == RightHandMode.Strum && near && previousPalm != null) {
            cross(previousPalm, u, dt, isReal = true)
            if (abs(palmVelocity) > 0.8) {
                val jump = (palmVelocity * LOOKAHEAD).coerceIn(-2.2 * posture.spacing, 2.2 * posture.spacing)
                cross(u, u + jump, LOOKAHEAD, isReal = false)
            }
        }

        if (mode == RightHandMode.Tchac && near) {
            if (inside && abs(palmVelocity) > 0.5 && time - lastTchacAt > 0.15) {
                lastTchacAt = time
                frame.tchacVelocity = minOf(1.0, 0.45 + abs(palmVelocity) * 0.22)
            }
            // O abafamento de repouso só conta logo DEPOIS de uma batida.
            if (inside && abs(palmVelocity) < 0.25 && time - lastTchacAt < 0.7) frame.dampAll = true
        }
        palmU = if (near) u else null

        // Quais pontas estão vivas depende do MODO.
        val activeTips: List<HandJoint> = when (mode) {
            RightHandMode.Thumb -> listOf(HandJoint.ThumbTip)
            RightHandMode.Fingerstyle -> listOf(HandJoint.ThumbTip, HandJoint.IndexTip, HandJoint.MiddleTip)
            RightHandMode.Strum, RightHandMode.Tchac -> emptyList()
        }

        for (joint in listOf(HandJoint.ThumbTip, HandJoint.IndexTip, HandJoint.MiddleTip, HandJoint.RingTip)) {
            val tip = hand[joint]
            val tipAlong = along(tip)
            val tipAcross = across(tip) - acrossOffset
            val tipNear = tipAlong > -1.10 && tipAlong < 0.95
            val previous = tipU[joint]
            if (joint in activeTips && tipNear && previous != null) {
                val v = (tipAcross - previous) / maxOf(dt, 0.008)
                cross(previous, tipAcross, dt, isReal = true)
                if (abs(v) > 0.4) {
                    val jump = (v * LOOKAHEAD).coerceIn(-1.5 * posture.spacing, 1.5 * posture.spacing)
                    cross(tipAcross, tipAcross + jump, LOOKAHEAD, isReal = false)
                }
            }
            if (tipNear) {
                tipU[joint] = tipAcross
                tipSeenAt[joint] = time
            } else if (time - (tipSeenAt[joint] ?: -1e9) > 0.4) {
                tipU.remove(joint)
            }
        }
    }

    // ── cruzamentos ────────────────────────────────────────────────────────

    /**
     * Toda corda cruzada entre duas amostras, com o instante interpolado. A
     * bandeira `armed` existe por uma razão: a PREVISÃO toca uma corda antes de
     * a mão chegar, e o cruzamento real não pode tocá-la de novo. Só a previsão
     * desarma; o cruzamento real sempre rearma.
     */
    internal fun cross(u0: Double, u1: Double, dt: Double, isReal: Boolean) {
        if (u0 == u1) return
        if (armed.size != stringCount) arm()
        val down = u1 > u0
        val velocity = (0.18 + abs(u1 - u0) / maxOf(dt, 0.004) / 4.5).coerceIn(0.15, 1.0)
        val hits = ArrayList<Pair<Int, Double>>()

        for (i in 0 until stringCount) {
            if (i < playable.size && !playable[i]) continue
            val position = stringU(i)
            val crossed = (position > u0 && position <= u1) || (position < u0 && position >= u1)
            if (crossed) {
                if (armed[i]) {
                    hits.add(i to (position - u0) / (u1 - u0))
                    if (!isReal) armed[i] = false
                } else if (isReal) {
                    armed[i] = true // dívida paga, em silêncio
                }
            } else if (isReal && !armed[i] && abs(u1 - position) > posture.spacing * 1.2) {
                // A previsão disparou e a mão recuou sem nunca cruzar.
                armed[i] = true
            }
        }

        if (hits.isEmpty()) return
        hits.sortBy { it.second }
        val first = hits[0].second
        for ((k, hit) in hits.withIndex()) {
            val (index, fraction) = hit
            if (now - lastHitAt[index] < RETRIGGER) continue
            lastHitAt[index] = now
            val v = velocity * (1 - k * 0.04) * (if (down) 1.0 else 0.86)
            pendingPlucks.add(Pluck(string = index, velocity = v.coerceIn(0.12, 1.0), delay = (fraction - first) * dt))
        }
    }

    // ── costuras de teste ──────────────────────────────────────────────────

    /** Coloca o instrumento sem passar pela calibração, para testar o cruzamento uma chamada por vez. */
    fun placeForTesting(body: Point, axis: Vector, length: Double, stringCount: Int, posture: AirGuitarPosture = AirGuitarPosture.standing) {
        this.body = body
        this.axis = axis
        this.length = length
        this.baseLength = length
        this.stringCount = stringCount
        this.posture = posture
        this.calibration = Calibration.Ready
        this.playable = BooleanArray(stringCount) { true }
        arm()
    }

    fun setPlayableForTesting(value: List<Boolean>) {
        playable = value.toBooleanArray()
    }

    fun setNowForTesting(value: Double) {
        now = value
    }

    val pendingPlucksForTesting: List<Pluck> get() = pendingPlucks.toList()

    fun clearPendingForTesting() = pendingPlucks.clear()

    val armedForTesting: List<Boolean> get() = armed.toList()

    companion object {
        /** Compensa a latência REAL da cadeia câmera → rastreador. 55 ms é o que o web mediu com MediaPipe a 20-25 fps. */
        const val LOOKAHEAD: Double = 0.055
        /** Histerese em SEGUNDOS: o web contava quadros (3 e 4 a 21 fps). */
        const val SHAPE_HOLD: Double = 0.143
        const val MODE_HOLD: Double = 0.190
        const val PINCH_HOLD: Double = 0.190
        /** Uma corda só soa de novo depois de ficar em paz por tanto tempo. */
        const val RETRIGGER: Double = 0.070
        /** Quão longe da VERTICAL as duas mãos têm de estar para a pose contar. ~13°. */
        const val MINIMUM_TILT: Double = 0.22
        /** E quão longe da HORIZONTAL. */
        const val MINIMUM_DROP: Double = 0.20

        /** Quanto o braço pode medir antes de o instrumento desenhado sair da tela. */
        internal fun roomFor(posture: AirGuitarPosture, stringCount: Int, axis: Vector, anchor: Point?, viewSize: Size): Double {
            if (anchor == null) return Double.MAX_VALUE
            return AirGuitarModel(posture, stringCount).fittingLength(axis, anchor, viewSize)
        }
    }
}
