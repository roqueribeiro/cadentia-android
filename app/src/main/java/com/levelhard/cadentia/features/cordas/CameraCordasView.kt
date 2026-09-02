package com.levelhard.cadentia.features.cordas

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.cordas.AirGuitarGeometry
import com.levelhard.cadentia.kit.cordas.CordaInstrument
import com.levelhard.cadentia.kit.cordas.HandChirality
import com.levelhard.cadentia.kit.cordas.HandChordMapping
import com.levelhard.cadentia.kit.cordas.HandJoint
import com.levelhard.cadentia.kit.cordas.HandLandmarks
import com.levelhard.cadentia.kit.cordas.PlayerHandedness
import com.levelhard.cadentia.kit.cordas.PoseHint
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.rememberReduceMotion
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * O modo câmera: o violão aparece entre as mãos — port do
 * `CameraCordasView.swift` (1.16).
 *
 * O vídeo fica numa `PreviewView` embaixo e o instrumento é desenhado por cima
 * num `Canvas`. Duas camadas em vez de empurrar quadros pelo Compose: os
 * quadros já estão na GPU. E o vídeo e o desenho vivem no MESMO retângulo, de
 * propósito: os landmarks são mapeados para este `Box`, então a prévia tem que
 * ser este `Box`, e não a tela inteira.
 */
@Composable
fun CameraCordasView(model: CordasModel, accent: Color, replay: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current
    val reduceMotion = rememberReduceMotion()
    val textMeasurer = rememberTextMeasurer()
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var showingChords by remember { mutableStateOf(false) }
    var frame by remember { mutableLongStateOf(0L) }

    var permissionLauncherReady by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionLauncherReady?.invoke(granted)
    }
    val tracker: HandTrackingSource = remember {
        if (replay) {
            ReplayHands()
        } else {
            MediaPipeHandTracker(context, lifecycleOwner) { requestPermission.launch(Manifest.permission.CAMERA) }
        }
    }
    LaunchedEffect(tracker) {
        (tracker as? MediaPipeHandTracker)?.let { mp -> permissionLauncherReady = { mp.onPermissionResult(it) } }
    }

    val viewSize = remember(sizePx, density) {
        com.levelhard.cadentia.kit.cordas.Size(sizePx.width / density.toDouble(), sizePx.height / density.toDouble())
    }

    DisposableEffect(tracker) {
        tracker.onFrame = { hands, size, time ->
            // O que o DETECTOR viu, ao lado do que a geometria recebeu: a
            // diferença é a de um rastreador cego para um filtro guloso.
            model.trackerInfo = "visao=${tracker.rawHandCount} fps=${tracker.framesPerSecond.roundToInt()} orient=${tracker.orientationName}"
            model.handleCamera(hands, size, time)
        }
        onDispose {
            tracker.stop()
            model.engine.dampAll(hard = true)
        }
    }
    LaunchedEffect(viewSize) {
        if (viewSize.width <= 0 || viewSize.height <= 0) return@LaunchedEffect
        if (tracker.status == HandTrackingStatus.Idle) tracker.start(viewSize) else tracker.updateViewSize(viewSize)
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) model.engine.decayAmplitudes(((now - last) / 1e9).coerceIn(0.001, 0.05))
                last = now
                frame = now
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { sizePx = it }
            .testTag("cordas.camera"),
    ) {
        tracker.previewView?.let { preview ->
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.46f)))
        }
        val strumHere = stringResource(R.string.cadentia_cordas_camera_strum_here)
        val edge = stringResource(R.string.cadentia_cordas_camera_edge)
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame
            if (viewSize.width <= 0) return@Canvas
            CameraPainter(this, model, density, textMeasurer, reduceMotion, tracker.framesPerSecond, strumHere, edge).draw()
        }

        Controls(model, tracker, accent, onChords = { showingChords = true })
        when {
            tracker.status == HandTrackingStatus.Denied -> DeniedCard(model)
            model.cameraFrame.calibration != AirGuitarGeometry.Calibration.Ready -> CalibrationCard(model)
        }
    }

    if (showingChords) HandChordSheet(model, CzTokens.gold) { showingChords = false }
}

// ── controles ────────────────────────────────────────────────────────────

/**
 * Seis controles, cada um dizendo o que é. Uma figura em pé num círculo não é
 * uma palavra; recalibrar era um toque em qualquer lugar — um gesto invisível
 * que jogava a calibração fora por acidente. À DIREITA: o braço sobe para o
 * canto superior esquerdo de um destro, e uma coluna de botões debaixo da mão
 * do braço é uma coluna de botões debaixo de uma mão.
 */
@Composable
private fun BoxScope.Controls(model: CordasModel, tracker: HandTrackingSource, accent: Color, onChords: () -> Unit) {
    val seated = model.geometry.posture.id == "sentado"
    val handedness = model.cameraFrame.handedness
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp),
    ) {
        CircleButton(Icons.Filled.Cameraswitch, stringResource(R.string.cadentia_cordas_camera_flip), "cordas.camera.flip") { tracker.flipCamera() }
        CircleButton(
            if (seated) Icons.Filled.AirlineSeatReclineNormal else Icons.Filled.Accessibility,
            stringResource(if (seated) R.string.cadentia_cordas_camera_posture_seated else R.string.cadentia_cordas_camera_posture_standing),
            "cordas.camera.posture",
        ) { model.geometry.setPosture(model.geometry.posture.flipped) }
        CircleButton(
            when (handedness) {
                PlayerHandedness.Right -> Icons.Filled.SwipeRight
                PlayerHandedness.Left -> Icons.Filled.SwipeLeft
                PlayerHandedness.Auto -> Icons.Filled.PanTool
            },
            stringResource(
                when (handedness) {
                    PlayerHandedness.Right -> R.string.cadentia_cordas_camera_handed_right
                    PlayerHandedness.Left -> R.string.cadentia_cordas_camera_handed_left
                    PlayerHandedness.Auto -> R.string.cadentia_cordas_camera_handed_auto
                },
            ),
            "cordas.camera.handed",
        ) {
            // Automático que não pode ser desmentido é um chute sem saída.
            model.geometry.setHandedness(
                when (model.geometry.handedness) {
                    PlayerHandedness.Auto -> PlayerHandedness.Right
                    PlayerHandedness.Right -> PlayerHandedness.Left
                    PlayerHandedness.Left -> PlayerHandedness.Auto
                },
            )
            model.haptics?.light()
        }
        CircleButton(
            Icons.Filled.Flip,
            stringResource(R.string.cadentia_cordas_camera_mirror) + " " + tracker.mapping.symbol, // i18n-verbatim: rótulo + símbolo do estado
            "cordas.camera.mirror",
            label = stringResource(R.string.cadentia_cordas_camera_mirror),
        ) { tracker.mapping = tracker.mapping.next }
        CircleButton(Icons.Filled.CenterFocusWeak, stringResource(R.string.cadentia_cordas_camera_recalibrate), "cordas.camera.recalibrate") {
            recalibrate(model)
        }
        // Configurar QUAL acorde cada gesto toca mora aqui e não no painel geral: só a câmera usa gesto.
        CircleButton(Icons.Filled.BackHand, stringResource(R.string.cadentia_cordas_hand_chords_title), "cordas.camera.handChords", onClick = onChords)
    }
}

private fun recalibrate(model: CordasModel) {
    model.geometry.reset()
    model.engine.dampAll(hard = true)
    model.haptics?.medium()
}

@Composable
private fun CircleButton(icon: ImageVector, caption: String, tag: String, label: String = caption, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        // Um botão cujo alvo é só o glifo fica inerte perto da barra de abas.
        modifier = Modifier
            .width(76.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp)
            .semantics { contentDescription = label }
            .testTag(tag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .background(Color.Black.copy(alpha = 0.78f), CircleShape)
                .border(1.5.dp, CzTokens.gold.copy(alpha = 0.6f), CircleShape),
        ) {
            Icon(icon, contentDescription = null, tint = CzTokens.gold, modifier = Modifier.size(19.dp))
        }
        Text(
            text = caption,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = CzTokens.gold.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Segure a pose: o cartão de calibração, que diz POR QUE quando recusa. */
@Composable
private fun CalibrationCard(model: CordasModel) {
    val frame = model.cameraFrame
    val lost = frame.calibration == AirGuitarGeometry.Calibration.Lost
    Box(Modifier.fillMaxSize().padding(start = 18.dp, end = 96.dp, top = 24.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.86f), RoundedCornerShape(16.dp))
                .border(1.5.dp, CzTokens.gold.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(18.dp)
                .testTag("cordas.camera.calibration"),
        ) {
            Text(
                text = stringResource(if (lost) R.string.cadentia_cordas_camera_lost else R.string.cadentia_cordas_camera_hold),
                fontSize = 15.sp, fontWeight = FontWeight.Black, color = CzTokens.gold, textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.cadentia_cordas_camera_calibrate_body),
                fontSize = 12.sp, color = CzTokens.textSecondary, textAlign = TextAlign.Center,
            )
            LinearProgressIndicator(
                progress = { frame.progress.toFloat().coerceIn(0f, 1f) },
                color = CzTokens.gold,
                trackColor = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.width(140.dp),
            )
            if (frame.poseHint != PoseHint.None) {
                Text(
                    text = stringResource(if (frame.poseHint == PoseHint.TooFlat) R.string.cadentia_cordas_camera_pose_flat else R.string.cadentia_cordas_camera_pose_upright),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CzTokens.warnAmber, textAlign = TextAlign.Center,
                )
            }
            if (lost) {
                Box(
                    modifier = Modifier
                        .background(CzTokens.gold, CircleShape)
                        .clickable { recalibrate(model) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(stringResource(R.string.cadentia_cordas_camera_recalibrate), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

/** Câmera recusada: o braço e os pads são o app inteiro menos um modo, e a tela diz isso. */
@Composable
private fun DeniedCard(model: CordasModel) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(18.dp))
                .padding(22.dp)
                .testTag("cordas.camera.denied"),
        ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = CzTokens.textTertiary, modifier = Modifier.size(26.dp))
            Text(stringResource(R.string.cadentia_cordas_camera_denied_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CzTokens.textPrimary)
            Text(stringResource(R.string.cadentia_cordas_camera_denied_body), fontSize = 12.sp, color = CzTokens.textSecondary, textAlign = TextAlign.Center)
            Box(
                modifier = Modifier
                    .background(CzTokens.gold, RoundedCornerShape(10.dp))
                    .clickable { model.mode = CordasModel.Mode.Chords }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.cadentia_cordas_camera_denied_action), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

// ── o desenho ────────────────────────────────────────────────────────────

/**
 * A tela do modo câmera: cordas paradas, mãos, acordes e leitura. **O violão
 * desenhado saiu daqui**: ele era escalado pelo rastreamento e virava um
 * violão minúsculo tocando nota abafada quando o rastreamento errava. As
 * cordas moram num lugar fixo da tela ([FixedStringsStrummer]); a mão esquerda
 * escolhe o acorde pelo gesto e a direita passa pelas cordas.
 */
private class CameraPainter(
    private val scope: DrawScope,
    private val model: CordasModel,
    private val density: Float,
    private val text: TextMeasurer,
    private val reduceMotion: Boolean,
    private val framesPerSecond: Double,
    private val strumHereLabel: String,
    private val edgeLabel: String,
) {
    private val width = scope.size.width / density.toDouble()
    private val height = scope.size.height / density.toDouble()
    private val accent = if (model.instrument.bodyStyle == CordaInstrument.Body.Solid) cordasElectricAccent else CzTokens.gold
    private val amplitude = model.engine.amplitude

    private fun px(value: Double): Float = (value * density).toFloat()
    private fun off(x: Double, y: Double) = Offset(px(x), px(y))

    private fun label(value: String, x: Double, y: Double, color: Color, sizeSp: Float, weight: FontWeight = FontWeight.Bold, mono: Boolean = false) {
        val measured = text.measure(
            value,
            TextStyle(fontSize = sizeSp.sp, fontWeight = weight, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        )
        scope.drawText(measured, color, Offset(px(x) - measured.size.width / 2f, px(y) - measured.size.height / 2f))
    }

    fun draw() {
        val frame = model.cameraFrame
        drawFixedStrings(frame)
        drawHands(frame)
        drawChordStrip()
        drawHUD(frame)
    }

    /** As cordas num lugar que não muda, sobre um painel escuro: uma parede clara apagaria as cordas. */
    private fun drawFixedStrings(frame: AirGuitarGeometry.Frame) {
        val strummer = model.strummer
        val count = model.instrument.stringCount
        val top = strummer.top * height
        val bottom = strummer.bottom * height
        val margin = 26.0
        val panelTop = top - margin
        val panelBottom = bottom + margin
        scope.drawRect(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.05f)),
                startY = px(panelTop), endY = px(panelBottom),
            ),
            off(0.0, panelTop), Size(px(width), px(panelBottom - panelTop)),
        )

        for (index in 0 until count) {
            val y = strummer.stringY(index, count) * height
            val spec = model.instrument.strings[index]
            val strokeWidth = (1.0 + spec.gauge.pow(1.7) * 3.2) * model.instrument.stringScale
            val level = amplitude.getOrElse(index) { 0.0 }
            val muted = model.frets.getOrElse(index) { 0 } < 0

            val path = Path()
            path.moveTo(0f, px(y))
            // A corda que soa ondula: a única confirmação visual de que a passada pegou.
            if (level > 0.05 && !reduceMotion) {
                val segments = 24
                for (step in 1..segments) {
                    val t = step.toDouble() / segments
                    val wobble = sin(t * 12.6 + index) * level * 7 * sin(PI * t)
                    path.lineTo(px(width * t), px(y + wobble))
                }
            } else {
                path.lineTo(px(width), px(y))
            }
            if (level > 0.05) {
                scope.drawPath(path, accent.copy(alpha = minOf(0.65, level * 1.6).toFloat()), style = Stroke(px(strokeWidth + 7)))
            }
            val color = when {
                muted -> Color(0.55f, 0.55f, 0.55f, 0.28f)
                spec.gauge > 0.45 -> Color(0.80f, 0.66f, 0.42f)
                else -> Color(0.95f, 0.95f, 0.95f)
            }
            scope.drawPath(path, color, style = Stroke(px(strokeWidth)))

            // A nota que a corda toca AGORA, na ponta esquerda: a única forma de
            // conferir se o gesto virou o acorde que se queria.
            val note = model.midi(index)?.let { MusicNotes.noteNames[((it % 12) + 12) % 12] } ?: "×"
            label(note, 18.0, y - 11, if (level > 0.06) accent else Color.White.copy(alpha = 0.65f), sizeSp = 11f, weight = FontWeight.Black, mono = true)
        }

        // Sem a mão da batida no quadro, as cordas sozinhas não explicam o que fazer.
        if (frame.pickHand == null) {
            label(strumHereLabel, width / 2, panelBottom + 18, Color.White.copy(alpha = 0.75f), sizeSp = 13f, weight = FontWeight.SemiBold)
        }
    }

    /**
     * O que a câmera está lendo, fininho: dourado é a mão do braço, branco é a
     * que bate. "Não identifica a mão" são dois defeitos numa frase — o
     * rastreador perdendo a mão, ou a geometria dando o papel errado — e
     * desenhar as juntas separa os dois num olhar.
     */
    private fun drawHands(frame: AirGuitarGeometry.Frame) {
        val bones = listOf(
            listOf(HandJoint.Wrist, HandJoint.ThumbCMC, HandJoint.ThumbMCP, HandJoint.ThumbIP, HandJoint.ThumbTip),
            listOf(HandJoint.Wrist, HandJoint.IndexMCP, HandJoint.IndexPIP, HandJoint.IndexDIP, HandJoint.IndexTip),
            listOf(HandJoint.IndexMCP, HandJoint.MiddleMCP, HandJoint.RingMCP, HandJoint.LittleMCP),
            listOf(HandJoint.MiddleMCP, HandJoint.MiddlePIP, HandJoint.MiddleDIP, HandJoint.MiddleTip),
            listOf(HandJoint.RingMCP, HandJoint.RingPIP, HandJoint.RingDIP, HandJoint.RingTip),
            listOf(HandJoint.Wrist, HandJoint.LittleMCP, HandJoint.LittlePIP, HandJoint.LittleDIP, HandJoint.LittleTip),
        )
        val tips = setOf(HandJoint.IndexTip, HandJoint.MiddleTip, HandJoint.RingTip, HandJoint.LittleTip, HandJoint.ThumbTip)
        for ((hand, tint) in listOf(frame.neckHand to CzTokens.gold, frame.pickHand to Color.White)) {
            val landmarks: HandLandmarks = hand ?: continue
            for (chain in bones) {
                val path = Path()
                for ((index, joint) in chain.withIndex()) {
                    val place = landmarks[joint]
                    if (index == 0) path.moveTo(px(place.x), px(place.y)) else path.lineTo(px(place.x), px(place.y))
                }
                scope.drawPath(path, tint.copy(alpha = 0.42f), style = Stroke(px(1.6)))
            }
            // Qual mão o app acredita que esta é.
            val wrist = landmarks[HandJoint.Wrist]
            val letter = when (landmarks.chirality) {
                HandChirality.Left -> "E"
                HandChirality.Right -> "D"
                HandChirality.Unknown -> "?"
            }
            label(letter, wrist.x, wrist.y + 20, tint, sizeSp = 13f, weight = FontWeight.Black)
            for (joint in HandJoint.entries) {
                val place = landmarks[joint]
                scope.drawCircle(tint.copy(alpha = 0.75f), px(if (joint in tips) 3.4 else 2.2), off(place.x, place.y))
            }
        }
    }

    /**
     * A legenda, FIXA embaixo e sempre horizontal: o nome grande e os quatro
     * pontos dos dedos, com a forma que a câmera está lendo acesa. Mostra as
     * nove formas, e o nome vem da mesma regra que o som — índice na lista de
     * formas, com volta — para a legenda não poder divergir do que se ouve.
     */
    private fun drawChordStrip() {
        val names = model.chordNames
        val shapes = HandChordMapping.shapes
        val slots = shapes.size
        if (names.isEmpty()) return
        val stripHeight = 62.0
        val y = height - stripHeight - 26
        scope.drawRoundRect(Color.Black.copy(alpha = 0.78f), off(10.0, y), Size(px(width - 20), px(stripHeight)), CornerRadius(px(14.0)))
        val slotWidth = (width - 20) / slots
        val active = HandChordMapping.shapeIndex(model.cameraFrame.confirmedMask)
        for (slot in 0 until slots) {
            val centreX = 10 + slotWidth * (slot + 0.5)
            val on = slot == active
            val name = names[slot % names.size]
            if (on) {
                scope.drawRoundRect(
                    accent.copy(alpha = 0.18f), off(10 + slotWidth * slot + 2, y + 3),
                    Size(px(slotWidth - 4), px(stripHeight - 6)), CornerRadius(px(9.0)),
                )
            }
            val scale = if (name.length > 3) 0.78 else 1.0
            label(name, centreX, y + 20, if (on) accent else Color.White.copy(alpha = 0.72f), sizeSp = ((if (on) 17 else 14) * scale).toFloat(), weight = if (on) FontWeight.Black else FontWeight.Bold)
            val shape = shapes[slot]
            for (finger in 0 until 4) {
                val lit = shape and (1 shl finger) != 0
                scope.drawCircle(
                    if (lit) (if (on) accent else Color.White.copy(alpha = 0.85f)) else Color.White.copy(alpha = 0.16f),
                    px(if (lit) 3.8 else 2.6),
                    off(centreX - 13.5 + finger * 9, y + 44),
                )
            }
        }
    }

    private fun drawHUD(frame: AirGuitarGeometry.Frame) {
        val value = frame.soloFret?.let { "casa $it" } ?: (model.chordId ?: "—") // i18n-verbatim: HUD de leitura, como no iOS
        scope.drawRoundRect(Color.Black.copy(alpha = 0.78f), off(width / 2 - 104, 10.0), Size(px(208.0), px(62.0)), CornerRadius(px(14.0)))
        label(value, width / 2, 34.0, accent, sizeSp = 27f, weight = FontWeight.Black)
        for (finger in 0 until 4) {
            val lit = frame.rawMask and (1 shl finger) != 0
            scope.drawCircle(if (lit) accent else Color.White.copy(alpha = 0.2f), px(if (lit) 6.0 else 4.5), off(width / 2 - 30 + finger * 20, 55.0))
        }
        // A taxa de quadros, pequena: toda constante de histerese da geometria
        // foi medida a 20-25 fps, e este é o número que diz se ainda valem.
        if (framesPerSecond > 0) {
            label(
                "${framesPerSecond.roundToInt()} fps", width / 2 - 78, 66.0, // i18n-verbatim: unidade
                if (framesPerSecond < 15) CzTokens.warnAmber else Color.White.copy(alpha = 0.45f),
                sizeSp = 9f, mono = true,
            )
        }
        if (frame.pickHandNearEdge) {
            label(edgeLabel, width / 2, height - 40, CzTokens.warnAmber, sizeSp = 12f)
        }
    }
}
