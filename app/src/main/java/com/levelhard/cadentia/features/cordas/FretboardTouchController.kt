package com.levelhard.cadentia.features.cordas

import com.levelhard.cadentia.kit.cordas.ChordTranspose
import com.levelhard.cadentia.kit.cordas.FretboardLayout
import com.levelhard.cadentia.kit.cordas.NailCapture
import com.levelhard.cadentia.kit.cordas.Point
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * O que os dedos no braço significam — port do `FretboardTouchController.swift`
 * (1.16), que veio do `public/src/input.js` do repo cordas.
 *
 * A parte que vale ler duas vezes é [assignFingers]: montar um acorde é
 * pousar três ou quatro dedos quase ao mesmo tempo, e decidir corda a corda na
 * ordem em que os toques chegam condena o último dedo — as cordas boas já
 * foram tomadas e ele fica sem lugar. Por isso a atribuição é do CONJUNTO
 * INTEIRO: a cada dedo novo, todos os dedos no braço são redistribuídos de uma
 * vez, cada um com a corda livre mais próxima, do par mais óbvio ao mais
 * duvidoso.
 *
 * As coordenadas são em PONTOS (dp), as mesmas do [FretboardLayout]; quem
 * chama converte os pixels do `MotionEvent`. O tempo é em segundos.
 */
class FretboardTouchController(private val scope: CoroutineScope = MainScope()) {
    /**
     * Onde o dedo pousou. `Bridge` é a faixa de baixo: uma mão descansando ali
     * é palm mute e nunca batida — é o que impede uma batida baixa de cair na
     * barra de abas.
     */
    enum class Zone { Rail, Neck, Strum, Bridge }

    private class Touch(
        var x: Double,
        var y: Double,
        var previousX: Double,
        var time: Double,
        /** Quando o dedo pousou. `still` só cresce em `moved`, e uma mão parada nunca acumulava. */
        val downTime: Double,
        var zone: Zone,
        var nail: Double,
    ) {
        var still = 0.0
        var fret = 0
        var baseX = 0.0
        var barre = 0
        var string: Int? = null
        var pad = -1
        var railBaseY = 0.0
        var railBaseX = 0.0
        var railBaseShift = 0
        var railMoved = false
        var railLocked = false
        var railStart = 0.0
    }

    var model: CordasModel? = null
    var layout: FretboardLayout? = null

    private val touches = LinkedHashMap<Int, Touch>()

    fun reset() {
        touches.clear()
        model?.palmMuted = false
        model?.let { it.nail.reset(it.instrument.stringCount) }
    }

    // ── entradas ─────────────────────────────────────────────────────────

    fun began(id: Int, x: Double, y: Double, radius: Double, time: Double) {
        val model = model ?: return
        val layout = layout ?: return
        val zone = zone(x, y, layout)
        val touch = Touch(
            x = x, y = y, previousX = x, time = time, downTime = time, zone = zone,
            nail = NailCapture.nailness(radius, model.nailEnabled),
        )
        when (zone) {
            Zone.Rail -> {
                touch.railBaseY = y
                touch.railBaseX = x
                touch.railBaseShift = model.shift
                touch.railLocked = y < layout.neckHeight
                touch.railStart = time
                touches[id] = touch
            }
            Zone.Neck -> {
                touches[id] = touch
                neckDown(id, time)
            }
            Zone.Strum -> {
                touches[id] = touch
                val plucks = model.nail.touchDown(
                    x, y, time, touch.nail,
                    muted = palmMuting(except = id, time, layout), layout = layout,
                )
                model.play(plucks)
                if (plucks.isNotEmpty()) model.haptics?.light()
            }
            Zone.Bridge -> {
                // A palma pousou no cavalete. Nada soa; o que for batido enquanto
                // ela descansa sai abafado — depois da espera, para um dedo de
                // passagem não abafar nada.
                touches[id] = touch
                val deadline = time + 0.16
                scope.launch(Dispatchers.Main) {
                    delay(170)
                    val current = this@FretboardTouchController.layout ?: return@launch
                    refreshPalmMute(deadline, current)
                }
            }
        }
    }

    fun moved(id: Int, x: Double, y: Double, radius: Double, time: Double) {
        val model = model ?: return
        val layout = layout ?: return
        val touch = touches[id] ?: return
        val dt = maxOf(0.001, time - touch.time)
        val movedBy = hypot(x - touch.x, y - touch.y)
        touch.previousX = touch.x
        touch.still = if (movedBy < 2) touch.still + dt else 0.0
        touch.x = x
        touch.y = y
        touch.time = time
        touch.nail = touch.nail * 0.6 + NailCapture.nailness(radius, model.nailEnabled) * 0.4

        refreshPalmMute(time, layout)
        if (touch.zone == Zone.Rail) {
            railMoved(id, dt)
            return
        }

        // Quem começou no braço e cruzou para a metade da batida vira unha, e
        // vice-versa. Cruzar para o cavalete vira palma em vez de seguir
        // batendo até a barra de abas.
        val zone = zone(x, y, layout)
        if (zone != touch.zone) {
            val previous = touch.zone
            touch.zone = zone
            if (previous == Zone.Neck) releaseNeck(touch)
            if (previous == Zone.Strum) model.nail.touchUp(x, y, time)
            if (zone == Zone.Neck) neckDown(id, time)
            return
        }
        if (zone == Zone.Bridge) return

        if (zone == Zone.Neck) {
            neckMoved(id)
        } else {
            val plucks = model.nail.sweep(
                touch.previousX, x, dt, touch.nail,
                muted = palmMuting(except = id, time, layout), layout = layout,
            )
            model.play(plucks)
            if (plucks.size > 2) model.haptics?.medium() else if (plucks.isNotEmpty()) model.haptics?.light()
        }
    }

    fun ended(id: Int, x: Double, y: Double, time: Double) {
        val model = model ?: return
        val touch = touches[id] ?: return
        when (touch.zone) {
            Zone.Neck -> {
                // O DEDO SAI DO CONJUNTO ANTES DE O CONJUNTO SER REDISTRIBUÍDO:
                // senão o dedo recém-levantado ganhava uma corda de volta e
                // apertava a casa velha nela de novo ("solto a casa e não volta
                // para corda solta").
                touches.remove(id)
                releaseNeck(touch)
            }
            Zone.Rail -> railEnded(id, time)
            Zone.Strum -> model.nail.touchUp(x, y, time)
            Zone.Bridge -> Unit
        }
        touches.remove(id)
        layout?.let { refreshPalmMute(time, it) }
    }

    // ── braço ────────────────────────────────────────────────────────────

    private fun neckDown(id: Int, time: Double) {
        val model = model ?: return
        val layout = layout ?: return
        val touch = touches[id] ?: return
        if (model.mode == CordasModel.Mode.Chords) {
            val index = layout.padGrid.index(Point(touch.x, touch.y))
            if (index != null) {
                model.setChord(index)
                touch.pad = index
                model.haptics?.light()
            }
            return
        }
        touch.fret = layout.fretAt(touch.y)
        touch.baseX = touch.x
        touch.barre = 0
        val previous = model.frets.getOrNull(layout.stringAt(touch.x)) ?: 0
        assignFingers()
        val string = touches[id]?.string ?: return

        // Sem mão direita: encostou na casa, a nota sai.
        if (model.autoPluck) {
            model.pluck(string, velocity = 0.62, nail = 0.45)
            model.haptics?.light()
            return
        }

        // Hammer-on e pull-off: se a corda já soa, muda a nota sem palhetar de novo.
        if ((model.engine.amplitude.getOrNull(string) ?: 0.0) > 0.08) {
            val delta = touch.fret - maxOf(previous, 0)
            model.pluck(string, velocity = if (delta > 0) 0.22 else 0.16, nail = 0.3)
        }
    }

    private fun neckMoved(id: Int) {
        val model = model ?: return
        val layout = layout ?: return
        val touch = touches[id] ?: return
        val string = touch.string ?: return
        val dx = touch.x - touch.baseX

        // Um dedo deitado segura várias cordas na mesma casa.
        if (abs(dx) > 70) {
            val from = layout.stringAt(touch.baseX)
            val to = layout.stringAt(touch.x)
            for (k in minOf(from, to)..maxOf(from, to)) {
                model.setFret(touch.fret, k)
                model.applyPitch(k)
            }
            touch.barre = abs(to - from)
            return
        }

        val newFret = layout.fretAt(touch.y)
        if (newFret == touch.fret || touch.barre != 0) return
        val delta = newFret - touch.fret

        if (neckFingerCount() >= 2 && ChordTranspose.fretted(model.frets).size >= 2) {
            // A MÃO INTEIRA: a forma anda pelo braço sem soltar o acorde. Sem
            // palhetada nova — a nota desliza, como um glissando.
            val moved = model.transposeShape(delta)
            if (moved != 0) {
                for (other in touches.values) if (other.zone == Zone.Neck) other.fret += moved
                model.haptics?.light()
            }
        } else {
            touch.fret = newFret
            model.setFret(newFret, string)
            if ((model.engine.amplitude.getOrNull(string) ?: 0.0) > 0.06) {
                model.applyPitch(string)
            } else {
                model.pluck(string, velocity = 0.14, nail = 0.3)
            }
            model.followShape()
        }
    }

    /** O toque já está FORA de `touches` quando isto roda. */
    private fun releaseNeck(touch: Touch) {
        val model = model ?: return
        val layout = layout ?: return
        if (model.mode == CordasModel.Mode.Chords) return
        val string = touch.string
        if (string == null) {
            // Um dedo que nunca ganhou corda ainda muda o pareamento dos que ficam.
            assignFingers()
            return
        }
        // A corda volta a solta, a menos que outro dedo a esteja segurando.
        val held = touches.values.any { other ->
            other !== touch && other.zone == Zone.Neck &&
                (other.string == string || (other.barre > 0 && layout.stringAt(other.x) == string))
        }
        if (!held) {
            model.setFret(model.capo, string)
            model.applyPitch(string)
        }
        // Um dedo a menos: os outros podem alcançar cordas melhores.
        assignFingers()
    }

    /**
     * O conjunto inteiro de uma vez, guloso sobre os pares ordenados por
     * distância: o dedo em cima de uma corda ganha ela, e os apertados
     * transbordam para as vizinhas.
     */
    private fun assignFingers() {
        val model = model ?: return
        val layout = layout ?: return
        val fingers = touches.filter { it.value.zone == Zone.Neck && it.value.barre == 0 }
        if (fingers.isEmpty()) return

        class Pair(val key: Int, val string: Int, val distance: Double)
        val pairs = ArrayList<Pair>()
        for ((key, touch) in fingers) {
            for (index in layout.stringX.indices) pairs += Pair(key, index, abs(layout.stringX[index] - touch.x))
        }
        pairs.sortBy { it.distance }

        val usedStrings = HashSet<Int>()
        val placed = HashSet<Int>()
        for (touch in fingers.values) touch.string = null
        for (pair in pairs) {
            if (pair.key in placed || pair.string in usedStrings) continue
            touches[pair.key]?.string = pair.string
            placed += pair.key
            usedStrings += pair.string
            if (placed.size == fingers.size) break
        }

        // Cordas presas por pestana guardam a casa; o resto volta a solta.
        val barred = HashSet<Int>()
        for (touch in touches.values) {
            if (touch.zone != Zone.Neck || touch.barre <= 0) continue
            val from = layout.stringAt(touch.baseX)
            val to = layout.stringAt(touch.x)
            for (k in minOf(from, to)..maxOf(from, to)) barred += k
        }
        for (key in placed) {
            val touch = touches[key] ?: continue
            val string = touch.string ?: continue
            model.setFret(touch.fret, string)
        }
        for (index in 0 until model.instrument.stringCount) {
            if (index in barred || index in usedStrings) continue
            if (model.frets.getOrNull(index) == model.capo) continue
            model.setFret(model.capo, index)
            // E a nota que ESTÁ SOANDO tem que seguir a casa.
            model.applyPitch(index)
        }
    }

    private fun neckFingerCount(): Int = touches.values.count { it.zone == Zone.Neck }

    /**
     * Uma mão descansando no cavalete é palm mute — de propósito. O contato
     * tem que estar debaixo das cordas e ter ficado ali: um roçar de passagem
     * não é abafamento, e um dedo segurando o telefone na borda também não.
     */
    private fun palmMuting(except: Int, time: Double, layout: FretboardLayout): Boolean {
        val first = layout.stringX.firstOrNull() ?: return false
        val last = layout.stringX.lastOrNull() ?: return false
        val margin = layout.laneHalfGap
        return touches.any { (key, touch) ->
            key != except && touch.zone == Zone.Bridge &&
                touch.x > first - margin && touch.x < last + margin &&
                time - touch.downTime > 0.15
        }
    }

    /** O que a tela tem que mostrar sobre isso. */
    private fun refreshPalmMute(time: Double, layout: FretboardLayout) {
        val muting = palmMuting(except = Int.MIN_VALUE, time, layout)
        val model = model ?: return
        if (model.palmMuted != muting) model.palmMuted = muting
    }

    // ── o trilho ─────────────────────────────────────────────────────────

    private fun railMoved(id: Int, dt: Double) {
        val model = model ?: return
        val layout = layout ?: return
        val touch = touches[id] ?: return
        if (!touch.railLocked) {
            val dx = abs(touch.x - touch.railBaseX)
            val dy = abs(touch.y - touch.railBaseY)
            if (touch.y > layout.neckHeight && touch.y < layout.strumBottom && dx > 14 && dx > dy) {
                // Era uma batida encostada na borda: devolve o dedo para a unha.
                touch.zone = Zone.Strum
                val plucks = model.nail.sweep(touch.railBaseX, touch.x, dt, touch.nail, muted = false, layout = layout)
                model.play(plucks)
                return
            }
            if (dy < 8) return
            touch.railLocked = true
            touch.railBaseY = touch.y
            touch.railBaseShift = model.shift
        }
        // O braço segue o dedo: uma casa a cada ~40 pt e NO MÁXIMO uma casa por
        // amostra, para um tranco nunca virar um salto de dez casas.
        val step = layout.size.height * 0.048
        var steps = ((touch.y - touch.railBaseY) / step).roundToInt()
        if (steps == 0) return
        steps = steps.coerceIn(-1, 1)
        val target = (touch.railBaseShift + steps).coerceIn(0, maxOf(0, model.instrument.frets - model.visibleFrets))
        if (target != model.shift) {
            model.goToPosition(target)
            touch.railMoved = true
            model.haptics?.light()
        }
        touch.railBaseY += steps * step
        touch.railBaseShift = model.shift
    }

    /** Pular para uma marca só num toque limpo: curto, sem arrasto nenhum. */
    private fun railEnded(id: Int, time: Double) {
        val model = model ?: return
        val layout = layout ?: return
        val touch = touches[id] ?: return
        if (touch.railMoved) return
        val quick = time - touch.railStart < 0.26
        val stationary = abs(touch.y - touch.railBaseY) < 8 && abs(touch.x - touch.railBaseX) < 8
        if (!quick || !stationary) return
        val marks = layout.railMarks
        var best: Int? = null
        var bestDistance = Double.MAX_VALUE
        for ((index, position) in marks.withIndex()) {
            val distance = abs(layout.railY(index, marks.size) - touch.y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = position
            }
        }
        if (best != null && bestDistance < layout.size.height * 0.075 && best != model.shift) {
            model.goToPosition(best)
            model.haptics?.medium()
        }
    }

    companion object {
        fun zone(x: Double, y: Double, layout: FretboardLayout): Zone {
            if (layout.hasRail && x < layout.railWidth) return Zone.Rail
            if (y >= layout.strumBottom) return Zone.Bridge
            return if (y < layout.neckHeight) Zone.Neck else Zone.Strum
        }
    }
}
