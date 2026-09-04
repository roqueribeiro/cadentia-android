package com.levelhard.cadentia.features.cordas

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.cordas.CordaInstrument
import com.levelhard.cadentia.kit.cordas.FretboardLayout
import com.levelhard.cadentia.kit.cordas.Rect
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.rememberReduceMotion
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A cor do Cordas elétrico (guitarra e baixo, pelo CORPO): o laranja quente do iOS. */
val cordasElectricAccent = Color(red = 1f, green = 0.54f, blue = 0.24f)

/**
 * O braço na tela — port do `FretboardView.swift` (1.16).
 *
 * O toque é `MotionEvent` cru, e não gesto do Compose, e isso não é
 * preferência: os gestos são de um dedo, e aqui três ou quatro dedos apertam
 * casas enquanto uma unha varre as cordas. `touchMajor` é o motivo de valer a
 * pena: ele diz o TAMANHO do contato, e é o que separa unha de polpa. As
 * amostras históricas do evento são os toques coalescidos do iOS: com uma unha
 * correndo rápido, cada amostra a mais é uma corda a menos perdida no caminho.
 *
 * O layout é em PONTOS (dp), como no iOS, para o tap target de 44 continuar
 * sendo 44 em qualquer densidade; o desenho e o toque convertem.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FretboardView(model: CordasModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val reduceMotion = rememberReduceMotion()
    val controller = remember { FretboardTouchController() }
    val textMeasurer = rememberTextMeasurer()
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    /** Redesenho por quadro enquanto alguma corda vibra. */
    var frame by remember { mutableLongStateOf(0L) }

    val layout = remember(
        sizePx, model.instrument.id, model.visibleFrets, model.shift, model.pixelsPerMillimetre,
        model.spread, model.mode, model.chordNames.size, model.handsFreeNeck, density,
    ) {
        if (sizePx.width == 0 || sizePx.height == 0) null
        else model.layout(com.levelhard.cadentia.kit.cordas.Size(sizePx.width / density.toDouble(), sizePx.height / density.toDouble()))
    }

    LaunchedEffect(layout) {
        controller.model = model
        controller.layout = layout
        if (layout != null) model.noteNeckHeight(layout.fretSpanHeight)
    }
    LaunchedEffect(model.instrument.id) { controller.reset() }

    // O relógio da vibração: 60 vezes por segundo o nível de cada corda cai,
    // em tempo de parede. Só redesenha quando há corda soando.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9).coerceIn(0.001, 0.05)
                    model.engine.decayAmplitudes(dt)
                }
                last = now
                if (model.engine.amplitude.any { it > 0.01 } || frame == 0L) frame = now
            }
        }
    }

    val neckLabel = stringResource(R.string.cadentia_cordas_a11y_neck)
    val strumLabel = stringResource(R.string.cadentia_cordas_a11y_strum)
    val mutedLabel = stringResource(R.string.cadentia_cordas_muted)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { sizePx = it }
            // Uma batida que começa na borda da tela é uma batida, não um
            // "voltar": o iOS desliga o pop interativo do NavigationStack em
            // cima do braço (`CordasView.swift`), aqui o braço sai da zona de
            // gesto do sistema. Achado do QA: a batida de borda saía da tela.
            .systemGestureExclusion()
            .testTag("cordas.neck")
            .semantics {
                contentDescription = neckLabel
                stateDescription = model.chordId ?: model.notesSummary
                // A ação de bater: é o que deixa quem não vê o braço tocar nele.
                customActions = listOf(
                    CustomAccessibilityAction(strumLabel) {
                        model.strumAll()
                        true
                    },
                )
            }
            .pointerInteropFilter { event -> handle(event, controller, density) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame
            val current = layout ?: return@Canvas
            FretboardPainter(this, model, current, density, textMeasurer, reduceMotion, mutedLabel).draw()
        }
        // Os pads de acorde como botões para o TalkBack (a
        // `accessibilityRepresentation` do iOS): nós invisíveis sobre cada
        // pad, sem tocar no caminho de toque, que continua no filtro acima.
        if (model.mode == CordasModel.Mode.Chords) {
            layout?.let { current ->
                for ((index, name) in model.chordNames.withIndex()) {
                    val rect = current.padGrid.rect(index) ?: continue
                    val selected = model.chordIndex == index
                    Box(
                        Modifier
                            .offset(rect.x.dp, rect.y.dp)
                            .size(rect.width.dp, rect.height.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = name
                                this.selected = selected
                                onClick {
                                    model.setChord(index)
                                    true
                                }
                            },
                    )
                }
            }
        }
    }
}

private fun handle(event: MotionEvent, controller: FretboardTouchController, density: Float): Boolean {
    fun x(index: Int) = event.getX(index) / density.toDouble()
    fun y(index: Int) = event.getY(index) / density.toDouble()
    fun radius(index: Int) = event.getTouchMajor(index) / density.toDouble() / 2
    val time = event.eventTime / 1000.0
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            val index = event.actionIndex
            controller.began(event.getPointerId(index), x(index), y(index), radius(index), time)
        }
        MotionEvent.ACTION_MOVE -> {
            for (index in 0 until event.pointerCount) {
                val id = event.getPointerId(index)
                for (h in 0 until event.historySize) {
                    controller.moved(
                        id,
                        event.getHistoricalX(index, h) / density.toDouble(),
                        event.getHistoricalY(index, h) / density.toDouble(),
                        event.getHistoricalTouchMajor(index, h) / density.toDouble() / 2,
                        event.getHistoricalEventTime(h) / 1000.0,
                    )
                }
                controller.moved(id, x(index), y(index), radius(index), time)
            }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val index = event.actionIndex
            controller.ended(event.getPointerId(index), x(index), y(index), time)
        }
        MotionEvent.ACTION_CANCEL -> {
            for (index in 0 until event.pointerCount) {
                controller.ended(event.getPointerId(index), x(index), y(index), time)
            }
        }
        else -> return false
    }
    return true
}

/** O desenho, em pontos convertidos para pixels — uma classe para não passar seis argumentos por função. */
private class FretboardPainter(
    private val scope: DrawScope,
    private val model: CordasModel,
    private val layout: FretboardLayout,
    private val density: Float,
    private val text: TextMeasurer,
    private val reduceMotion: Boolean,
    /** "Abafado", já localizado por quem constrói a tela. */
    private val mutedLabel: String,
) {
    private val width = layout.size.width
    private val height = layout.size.height
    private val solid = model.instrument.bodyStyle == CordaInstrument.Body.Solid
    private val glow = if (solid) cordasElectricAccent else CzTokens.gold
    private val amplitude = model.engine.amplitude

    private fun px(value: Double): Float = (value * density).toFloat()
    private fun off(x: Double, y: Double) = Offset(px(x), px(y))
    private fun size(w: Double, h: Double) = Size(px(w), px(h))

    private fun fillRect(x: Double, y: Double, w: Double, h: Double, color: Color) {
        if (w <= 0 || h <= 0) return
        scope.drawRect(color, off(x, y), size(w, h))
    }

    private fun fillRect(x: Double, y: Double, w: Double, h: Double, brush: Brush) {
        if (w <= 0 || h <= 0) return
        scope.drawRect(brush, off(x, y), size(w, h))
    }

    private fun vertical(colors: List<Color>, top: Double, bottom: Double) =
        Brush.verticalGradient(colors, startY = px(top), endY = px(bottom))

    private fun label(
        value: String, x: Double, y: Double, color: Color,
        sizeSp: Float = 12f, weight: FontWeight = FontWeight.Bold, mono: Boolean = true,
    ) {
        val measured = text.measure(
            value,
            TextStyle(
                fontSize = sizeSp.sp, fontWeight = weight,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            ),
        )
        scope.drawText(
            measured, color,
            topLeft = Offset(px(x) - measured.size.width / 2f, px(y) - measured.size.height / 2f),
        )
    }

    fun draw() {
        val neckHeight = layout.neckHeight
        val strumBottom = layout.strumBottom

        // Sem mão direita não há corpo: o braço desce até o cavalete.
        if (layout.handsFree) {
            drawNeck()
            drawOpenBand()
            drawStrings()
            // Os nomes das cordas soltas vêm DEPOIS das cordas: a mais grave é
            // a mais gorda e passava por cima do próprio nome.
            drawOpenLabels()
            drawBridge()
            if (layout.hasRail) drawRail()
            return
        }

        // O corpo, entre o braço e o cavalete.
        fillRect(
            0.0, neckHeight, width, strumBottom - neckHeight,
            vertical(
                if (solid) listOf(Color(0.23f, 0.08f, 0.13f), Color(0.10f, 0.03f, 0.05f))
                else listOf(Color(0.42f, 0.27f, 0.15f), Color(0.35f, 0.23f, 0.13f)),
                neckHeight, strumBottom,
            ),
        )
        if (solid) {
            // Captadores: é o que faz um corpo maciço ler como instrumento
            // elétrico em vez de tábua escura. Dois, onde ficam de verdade.
            val span = strumBottom - neckHeight
            for ((offset, h) in listOf(0.30 to 0.115, 0.66 to 0.095)) {
                val rect = Rect(width * 0.13, neckHeight + span * offset, width * 0.74, span * h)
                scope.drawRoundRect(
                    vertical(listOf(Color(0.13f, 0.13f, 0.13f), Color(0.05f, 0.05f, 0.05f)), rect.minY, rect.maxY),
                    off(rect.x, rect.y), size(rect.width, rect.height), CornerRadius(px(3.0)),
                )
                scope.drawRoundRect(
                    Color(0.32f, 0.32f, 0.32f, 0.8f), off(rect.x, rect.y), size(rect.width, rect.height),
                    CornerRadius(px(3.0)), style = Stroke(px(1.0)),
                )
                // Os polos, um por corda: o detalhe que diz "captador".
                val radius = min(2.6, rect.height * 0.16)
                for (x in layout.stringX) {
                    scope.drawCircle(Color(0.55f, 0.55f, 0.55f, 0.85f), px(radius), off(x, rect.minY + rect.height / 2))
                }
            }
        } else {
            val cx = width / 2
            val cy = neckHeight + (strumBottom - neckHeight) * 0.44
            val radius = min(width, strumBottom - neckHeight) * 0.30
            scope.drawCircle(Color.Black.copy(alpha = 0.85f), px(radius), off(cx, cy))
            scope.drawCircle(CzTokens.gold.copy(alpha = 0.5f), px(radius * 1.13), off(cx, cy), style = Stroke(px(3.0)))
        }

        // O braço, ou os pads.
        if (model.mode == CordasModel.Mode.Chords) {
            fillRect(0.0, 0.0, width, neckHeight, Color(0.04f, 0.04f, 0.06f))
            drawPads()
        } else {
            drawNeck()
        }

        drawStrings()
        drawBridge()
        if (layout.hasRail) drawRail()
    }

    /** A faixa das cordas soltas, no topo do braço sem mão direita. */
    private fun drawOpenBand() {
        val bandHeight = layout.openBandHeight
        fillRect(0.0, 0.0, width, bandHeight, vertical(listOf(Color(0.09f, 0.09f, 0.09f), Color(0.04f, 0.04f, 0.04f)), 0.0, bandHeight))
        // A pestana: a linha grossa e clara que diz onde o braço começa.
        fillRect(0.0, bandHeight - 3.5, width, 3.5, Color(0.93f, 0.90f, 0.82f))
    }

    /** O nome da nota de cada corda solta, num disco escuro em cima das cordas. */
    private fun drawOpenLabels() {
        val centre = layout.openBandHeight / 2
        for ((index, x) in layout.stringX.withIndex()) {
            val open = model.instrument.strings.getOrNull(index)?.midi ?: continue
            val note = MusicNotes.noteNames[(((open + model.capo) % 12) + 12) % 12]
            val level = amplitude.getOrElse(index) { 0.0 }
            scope.drawCircle(Color.Black.copy(alpha = 0.82f), px(11.0), off(x, centre))
            label(note, x, centre, if (level > 0.06) glow else Color.White.copy(alpha = 0.85f), weight = FontWeight.Black)
        }
    }

    private fun drawNeck() {
        val neckHeight = layout.neckHeight
        fillRect(
            0.0, 0.0, width, neckHeight,
            Brush.horizontalGradient(
                listOf(Color(0.14f, 0.08f, 0.03f), Color(0.26f, 0.16f, 0.08f), Color(0.13f, 0.07f, 0.03f)),
                startX = 0f, endX = px(width),
            ),
        )
        if (model.shift == 0) fillRect(0.0, 0.0, width, 7.0, Color(1f, 0.99f, 0.96f))
        for (k in 1..layout.visibleFrets) {
            val y = layout.fretY[k]
            fillRect(0.0, y - 1.8, width, 3.4, Color(0.88f, 0.88f, 0.88f))
            val number = model.shift + k
            val middle = (layout.fretY[k - 1] + y) / 2
            if (number in listOf(3, 5, 7, 9, 15, 17, 19, 21)) {
                scope.drawCircle(Color.White.copy(alpha = 0.5f), px(7.0), off(width / 2, middle))
            } else if (number == 12 || number == 24) {
                for (fraction in listOf(0.30, 0.70)) {
                    scope.drawCircle(Color.White.copy(alpha = 0.5f), px(7.0), off(width * fraction, middle))
                }
            }
            // O único rótulo do braço: é o que diz em que posição se está.
            label(number.toString(), layout.railWidth + 14, y - 11, Color.White.copy(alpha = 0.62f))
        }
    }

    private fun drawPads() {
        val names = model.chordNames
        val grid = layout.padGrid
        for ((index, name) in names.withIndex()) {
            val rect = grid.rect(index) ?: continue
            val on = model.chordIndex == index
            scope.drawRoundRect(
                if (on) CzTokens.gold else Color(0.09f, 0.09f, 0.11f),
                off(rect.x, rect.y), size(rect.width, rect.height), CornerRadius(px(12.0)),
            )
            scope.drawRoundRect(
                if (on) Color.White.copy(alpha = 0.8f) else CzTokens.hairline,
                off(rect.x, rect.y), size(rect.width, rect.height), CornerRadius(px(12.0)), style = Stroke(px(1.2)),
            )
            label(
                name, rect.x + rect.width / 2, rect.y + rect.height / 2 - 2,
                if (on) Color.Black.copy(alpha = 0.85f) else CzTokens.textPrimary,
                sizeSp = min(rect.height * 0.34, 26.0).toFloat(), mono = false,
            )
        }
    }

    private fun drawStrings() {
        val top = if (model.mode == CordasModel.Mode.Chords) layout.neckHeight else 0.0
        // A corda termina NO rastilho. Tudo depois é cavalete.
        val saddle = layout.strumBottom + 7
        for (index in layout.stringX.indices) {
            val x = layout.stringX[index]
            val fret = model.frets.getOrElse(index) { 0 }
            val muted = fret < 0
            val gauge = model.instrument.strings[index].gauge
            // `stringScale` é o que faz corda de baixo parecer corda de baixo.
            val strokeWidth = (1.0 + gauge.pow(1.7) * 4.4) * model.instrument.stringScale
            val level = amplitude.getOrElse(index) { 0.0 }
            val pressY = if (fret > model.shift && fret <= model.shift + layout.visibleFrets) layout.fretY[fret - model.shift] else 0.0
            val nodeY = maxOf(pressY, top)

            val path = Path()
            path.moveTo(px(x), px(top))
            // Reduce Motion cala a vibração da corda, não a corda: o brilho ainda diz qual soa.
            if (level > 0.05 && !reduceMotion) {
                path.lineTo(px(x), px(nodeY))
                val segments = 16
                val length = saddle - nodeY
                for (step in 1..segments) {
                    val t = step.toDouble() / segments
                    val y = nodeY + length * t
                    val wobble = sin(t * 9.42 + index) * level * (0.9 + gauge * 1.8) * sin(PI * t)
                    path.lineTo(px(x + wobble), px(y))
                }
            } else {
                path.lineTo(px(x), px(saddle))
            }
            val shadow = Path().apply { addPath(path, Offset(px(1.3 + strokeWidth * 0.25), 0f)) }
            scope.drawPath(shadow, Color.Black.copy(alpha = 0.42f), style = Stroke(px(strokeWidth + 1.1)))
            val color = when {
                muted -> Color(0.5f, 0.5f, 0.5f, 0.28f)
                gauge > 0.45 -> Color(0.72f, 0.56f, 0.31f)
                else -> Color(0.98f, 0.98f, 0.98f)
            }
            scope.drawPath(path, color, style = Stroke(px(strokeWidth), cap = StrokeCap.Round))
            if (level > 0.05) {
                scope.drawPath(path, glow.copy(alpha = (level * 0.22).toFloat().coerceIn(0f, 1f)), style = Stroke(px(strokeWidth + 4)))
            }
        }
    }

    private fun drawRail() {
        val rail = FretboardLayout.RAIL_WIDTH
        fillRect(0.0, 0.0, rail, height, Color(0.03f, 0.03f, 0.05f, 0.94f))
        val marks = layout.railMarks
        for ((index, position) in marks.withIndex()) {
            val y = layout.railY(index, marks.size)
            val on = position == model.shift
            scope.drawCircle(if (on) CzTokens.gold else Color.White.copy(alpha = 0.10f), px(12.0), off(rail / 2, y))
            label(position.toString(), rail / 2, y, if (on) Color.Black.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.55f), sizeSp = 11f)
        }
    }

    /** O cavalete: o rastilho, os pinos e a nota em que cada corda está agora. */
    private fun drawBridge() {
        val rect = layout.bridgeRect
        if (rect.height <= 0) return
        fillRect(
            rect.x, rect.y, rect.width, rect.height,
            vertical(
                if (solid) listOf(Color(0.13f, 0.05f, 0.07f), Color(0.06f, 0.02f, 0.03f))
                else listOf(Color(0.19f, 0.11f, 0.06f), Color(0.10f, 0.06f, 0.03f)),
                rect.minY, rect.maxY,
            ),
        )
        // O rastilho, onde as cordas param.
        fillRect(
            0.0, rect.minY + 5, rect.width, 3.2,
            when {
                model.palmMuted -> CzTokens.warnAmber
                solid -> Color(0.72f, 0.72f, 0.72f)
                else -> Color(0.93f, 0.90f, 0.82f)
            },
        )
        // Abafamento invisível parece app travado: ele aparece.
        if (model.palmMuted) {
            fillRect(rect.x, rect.y, rect.width, rect.height, CzTokens.warnAmber.copy(alpha = 0.10f))
            label(mutedLabel, width - 34, rect.minY + rect.height / 2 + 5, CzTokens.warnAmber, sizeSp = 9f, weight = FontWeight.Black, mono = false)
        }

        val drawn = HashSet<Int>()
        for (index in layout.stringX.indices) {
            if (model.instrument.courseCount < model.instrument.stringCount && index % 2 == 1) continue
            val x = layout.stringX[index].coerceIn(layout.railWidth + 16, width - 16)
            if (!drawn.add(x.roundToInt())) continue
            val fret = model.frets.getOrElse(index) { 0 }
            val note = model.midi(index)?.let { MusicNotes.noteNames[((it % 12) + 12) % 12] } ?: "×"
            val level = amplitude.getOrElse(index) { 0.0 }
            label(
                note, x, rect.minY + rect.height / 2 + 5,
                when {
                    level > 0.06 -> CzTokens.gold
                    fret < 0 -> Color.White.copy(alpha = 0.3f)
                    else -> Color.White.copy(alpha = 0.7f)
                },
                weight = FontWeight.Black,
            )
        }
    }
}
