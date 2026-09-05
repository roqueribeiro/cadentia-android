package com.levelhard.cadentia.features.cordas

import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.I18nMap
import com.levelhard.cadentia.LocalQaFlags
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.cordas.CordaInstrument
import com.levelhard.cadentia.ui.CzSlider
import com.levelhard.cadentia.ui.CzSwitch
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import kotlinx.coroutines.delay

/**
 * Cordas — o telefone vira o instrumento. Port do `CordasView.swift` (1.16).
 *
 * Três jeitos de tocar a mesma corda: o braço, os pads de acorde e a câmera.
 * Nascido do `phelipiii/cordas` — ver o crédito na tela Sobre.
 *
 * @param opening com qual instrumento a tela abre. O hub usa isto para o card
 *   do Baixo cair direto no baixo; vale UMA vez, na primeira entrada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CordasScreen(opening: CordaInstrument? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val qa = LocalQaFlags.current
    val model = remember { CordasModel(context) }
    var showPanel by remember { mutableStateOf(false) }
    var coach by remember { mutableStateOf<CordasModel.Mode?>(null) }
    var openingApplied by remember { mutableStateOf(false) }

    /** O tamanho real do instrumento NESTE aparelho, para o painel não medir uma tela que ninguém segura. */
    var boardSize by remember { mutableStateOf(IntSize.Zero) }

    // A cor da tela segue o CORPO, não o barramento: o baixo é elétrico aos
    // olhos e acústico aos ouvidos, e quem manda aqui são os olhos.
    val accent = if (model.instrument.bodyStyle == CordaInstrument.Body.Solid) cordasElectricAccent else CzTokens.gold

    fun offerCoach(mode: CordasModel.Mode) {
        if (!qa.cordasCoach && (qa.tab != null || model.preferences.coachSeen(mode))) return
        // Entrar na câmera pela primeira vez também levanta o pedido de
        // permissão do sistema; dois cartões de uma vez é pilha, não explicação.
        if (mode == CordasModel.Mode.Camera && !qa.cordasCoach && !CameraPermission.granted(context)) return
        coach = mode
    }

    fun dismissCoach() {
        coach?.let { model.preferences.markCoach(it) }
        coach = null
    }

    DisposableEffect(Unit) {
        model.haptics = ViewHaptics(view)
        model.engine.start()
        if (Log.isLoggable(CordasModel.TELEMETRY_TAG, Log.INFO)) {
            Log.i(
                CordasModel.TELEMETRY_TAG,
                "CORDAS motor: burst=${"%.2f".format(java.util.Locale.ROOT, model.engine.burstSeconds * 1000)} ms " +
                    "falhou=${model.engine.failed}",
            )
        }
        onDispose {
            model.engine.stop()
            model.haptics = null
        }
    }

    // Os argumentos de QA e o instrumento de abertura, uma vez.
    LaunchedEffect(Unit) {
        if (opening != null && !openingApplied) {
            model.instrument = opening
            openingApplied = true
        }
        CordasModel.Mode.named(qa.cordasMode)?.let { model.mode = it }
        qa.cordasInstrument?.let { model.instrument = CordaInstrument.named(it) }
        when (qa.cordasHandsFree) {
            true -> model.autoPluck = true
            false -> model.autoPluck = false
            null -> Unit
        }
        if (qa.cordasPanel) showPanel = true
        offerCoach(model.mode)
        if (qa.cordasSelftest) {
            // Toca um acorde em cada instrumento sem dedo nenhum: o crash que
            // importava só acontecia com um buffer agendado de verdade.
            for (instrument in CordaInstrument.all) {
                model.instrument = instrument
                delay(1200)
                model.setChord(model.chordNames[0])
                for (string in 0 until model.instrument.stringCount) {
                    model.pluck(string, velocity = 0.85, delay = string * 0.03, nail = 0.6)
                }
                delay(1000)
                model.engine.tchac(0.8)
            }
            delay(1000)
            Log.i(CordasModel.TELEMETRY_TAG, "QA-CORDAS-SELFTEST-OK xruns=${model.engine.xrunCount}")
        }
    }
    LaunchedEffect(model.mode) { offerCoach(model.mode) }

    // O gesto de voltar do sistema NÃO sai do Cordas com o braço na tela: uma
    // batida que começa na borda é batida. O `systemGestureExclusion` do braço
    // só vale para 200 dp de altura (teto do Android), então acima disso o
    // sistema ainda reconhece o gesto — e aqui ele vira nada. O iOS desliga o
    // pop interativo do NavigationStack pelo mesmo motivo; a saída é o botão
    // de voltar da barra (`cordas.back`). Achado do androidTest
    // `strummingFromTheEdgeNeverLeavesTheCordasScreen` (05/09).
    BackHandler(enabled = model.mode != CordasModel.Mode.Camera) { }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        Column(Modifier.fillMaxSize()) {
            Header(accent, onBack, onHelp = { coach = model.mode }, onPanel = { showPanel = true })
            ModeBar(model, accent)
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { boardSize = it }) {
                when (model.mode) {
                    CordasModel.Mode.Frets, CordasModel.Mode.Chords -> FretboardView(model)
                    CordasModel.Mode.Camera -> CameraCordasView(model, accent, replay = qa.cordasReplay)
                }
            }
        }
        AnimatedVisibility(visible = coach != null, enter = fadeIn(), exit = fadeOut()) {
            coach?.let { CordasCoach(mode = it, onDismiss = ::dismissCoach) }
        }
    }

    if (showPanel) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = qa.cordasPanel)
        ModalBottomSheet(onDismissRequest = { showPanel = false }, sheetState = sheetState, containerColor = CzTokens.stageTop) {
            CordasPanel(model, accent, boardSize, onDone = { showPanel = false })
        }
    }
}

/** A barra de cima: voltar, título, ajuda e o painel. */
@Composable
private fun Header(accent: Color, onBack: () -> Unit, onHelp: () -> Unit, onPanel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("cordas.back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = accent)
        }
        Text(
            text = stringResource(R.string.cadentia_cordas_title),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = CzTokens.textPrimary,
            // Centrado como no iOS e como os outros destinos do hub: o voltar
            // pesa 48 dp à esquerda e os dois botões 96 à direita, então o
            // título ganha 48 dp de folga à esquerda para cair no meio.
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f).padding(start = 48.dp),
        )
        IconButton(onClick = onHelp, modifier = Modifier.testTag("cordas.help")) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.cadentia_cordas_help), tint = accent)
        }
        IconButton(onClick = onPanel, modifier = Modifier.testTag("cordas.panel")) {
            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.cadentia_cordas_panel_title), tint = accent)
        }
    }
}

/**
 * Uma linha, não duas: o modo troca o tempo todo e fica segmentado; o
 * instrumento se escolhe de vez em quando e virou menu.
 */
@Composable
private fun ModeBar(model: CordasModel, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
    ) {
        InstrumentMenu(model, accent)
        ModeSegmented(model, accent, Modifier.weight(1f))
    }
}

@Composable
private fun InstrumentMenu(model: CordasModel, accent: Color) {
    var open by remember { mutableStateOf(false) }
    val name = stringResource(I18nMap.res(model.instrument.nameKey))
    val label = stringResource(R.string.cadentia_cordas_instrument)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .semantics { contentDescription = "$label: $name" } // i18n-verbatim: rótulo + valor, os dois já localizados
                .testTag("cordas.instrument"),
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (instrument in CordaInstrument.all) {
                DropdownMenuItem(
                    text = { Text(stringResource(I18nMap.res(instrument.nameKey))) },
                    trailingIcon = {
                        if (instrument.id == model.instrument.id) Icon(Icons.Filled.Check, contentDescription = null, tint = accent)
                    },
                    onClick = {
                        model.instrument = instrument
                        open = false
                    },
                    modifier = Modifier.testTag("cordas.instrument.${instrument.id}"),
                )
            }
        }
    }
}

@Composable
private fun ModeSegmented(model: CordasModel, accent: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.08f), shape)
            .padding(3.dp),
    ) {
        for (mode in model.availableModes) {
            val on = model.mode == mode
            val res = when (mode) {
                CordasModel.Mode.Frets -> R.string.cadentia_cordas_mode_frets
                CordasModel.Mode.Chords -> R.string.cadentia_cordas_mode_chords
                CordasModel.Mode.Camera -> R.string.cadentia_cordas_mode_camera
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .background(if (on) accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { model.mode = mode }
                    .testTag("cordas.mode.${mode.id}"),
            ) {
                Text(
                    text = stringResource(res),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) Color.Black.copy(alpha = 0.9f) else CzTokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── o painel ─────────────────────────────────────────────────────────────

@Composable
private fun CordasPanel(model: CordasModel, accent: Color, boardSize: IntSize, onDone: () -> Unit) {
    val density = LocalDensity.current.density
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp)
            .testTag("cordas.panel.sheet"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.cadentia_cordas_panel_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = CzTokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.cadentia_cordas_panel_done),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier.clickable(onClick = onDone).padding(8.dp).testTag("cordas.panel.done"),
            )
        }

        PanelSection(R.string.cadentia_cordas_panel_sound) {
            PanelSlider(R.string.cadentia_cordas_panel_volume, model.volume, 0.0..1.0, accent) { model.volume = it }
            PanelSlider(R.string.cadentia_cordas_panel_sustain, model.sustain, 0.4..1.6, accent) { model.sustain = it }
            PanelSlider(R.string.cadentia_cordas_panel_ambience, model.ambience, 0.0..0.5, accent) { model.ambience = it }
            if (model.instrument.isElectric) {
                PanelSlider(R.string.cadentia_cordas_panel_drive, model.driveAmount, 0.0..1.0, accent) { model.driveAmount = it }
            }
        }

        PanelSection(R.string.cadentia_cordas_panel_play) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.cadentia_cordas_panel_auto_pluck), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CzTokens.textPrimary)
                    Text(stringResource(R.string.cadentia_cordas_panel_auto_pluck_hint), fontSize = 11.sp, color = CzTokens.textTertiary)
                }
                CzSwitch(
                    checked = model.autoPluck,
                    onCheckedChange = { model.autoPluck = it },
                    accent = accent,
                    modifier = Modifier.testTag("cordas.autoPluck"),
                )
            }
        }

        PanelSection(R.string.cadentia_cordas_panel_neck) {
            val none = stringResource(R.string.cadentia_cordas_panel_capo_none)
            PanelCounter(R.string.cadentia_cordas_panel_capo, model.capo, 0..9, accent, { if (it == 0) none else it.toString() }) { model.capo = it }
            PanelCounter(R.string.cadentia_cordas_panel_frets, model.visibleFrets, 3..maxOf(3, model.maxVisibleFrets), accent, { it.toString() }) { model.visibleFrets = it }
            PanelSlider(R.string.cadentia_cordas_panel_spread, model.spread, 0.85..1.25, accent) { model.spread = it }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cadentia_cordas_panel_nail), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CzTokens.textPrimary, modifier = Modifier.weight(1f))
                CzSwitch(checked = model.nailEnabled, onCheckedChange = { model.nailEnabled = it }, accent = accent)
            }
        }

        MeasurementsSection(model, accent, boardSize, density)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(CzTokens.surface, RoundedCornerShape(12.dp))
                .clickable { model.engine.dampAll(hard = true) }
                .padding(vertical = 12.dp)
                .testTag("cordas.panel.silence"),
        ) {
            Text(stringResource(R.string.cadentia_cordas_panel_silence), color = CzTokens.danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Um instrumento na tela precisa saber o quanto mente, e a única forma honesta
 * é medir: o lado curto de um cartão contra a régua, e daí em diante os
 * milímetros são de verdade. Os números vêm do tamanho REAL do braço neste
 * aparelho, e a régua nunca passa da linha sem avisar.
 */
@Composable
private fun MeasurementsSection(model: CordasModel, accent: Color, boardSize: IntSize, density: Float) {
    var rulerWidthPx by remember { mutableStateOf(320f * density) }
    val size = com.levelhard.cadentia.kit.cordas.Size(
        (if (boardSize.width > 0) boardSize.width else 390) / density.toDouble(),
        (if (boardSize.height > 0) boardSize.height else 780) / density.toDouble(),
    )
    val layout = model.layout(size)
    val measured = layout.measurements
    val widest = maxOf(4.0, (rulerWidthPx / density - 6) / 53.98)
    val gaps = maxOf(1, model.instrument.courseCount - 1)
    val textMeasurer = rememberTextMeasurer()

    PanelSection(R.string.cadentia_cordas_panel_measures) {
        PanelSlider(
            R.string.cadentia_cordas_panel_ruler, model.pixelsPerMillimetre, 3.5..widest, accent,
            detail = { "%.2f px/mm".format(java.util.Locale.ROOT, it) }, // i18n-verbatim: unidade
        ) { model.pixelsPerMillimetre = it }
        // A régua: o cartão vai aqui. Marcas a cada centímetro.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .onSizeChanged { rulerWidthPx = it.width.toFloat() },
        ) {
            val perMillimetre = model.pixelsPerMillimetre * density
            val width = minOf((53.98 * perMillimetre).toFloat(), this.size.width)
            drawRoundRect(CzTokens.gold, Offset.Zero, Size(width, this.size.height), CornerRadius(3.dp.toPx()))
            for (centimetre in 1..5) {
                val x = (centimetre * 10 * perMillimetre).toFloat()
                if (x >= width) break
                drawRect(Color.Black.copy(alpha = 0.55f), Offset(x, 0f), Size(1.dp.toPx(), 7.dp.toPx()))
            }
            val label = textMeasurer.measure("54 mm", TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Black)) // i18n-verbatim: medida
            drawText(label, Color.Black, Offset(width / 2 - label.size.width / 2, this.size.height / 2 - label.size.height / 2 + 1.dp.toPx()))
        }
        // Os vãos ENTRE cordas, não o número de cordas: a viola tem cinco ordens e o baixo quatro cordas.
        PanelValue(
            R.string.cadentia_cordas_panel_spacing,
            "%.1f mm · real %d–%d".format( // i18n-verbatim: números e unidade
                java.util.Locale.getDefault(), measured.spacing,
                (measured.real.atNut / gaps).toInt(), (measured.real.atBridge / gaps).toInt(),
            ),
        )
        PanelValue(
            R.string.cadentia_cordas_panel_scale,
            "%d mm · real %d mm".format(java.util.Locale.getDefault(), measured.equivalentScale.toInt(), measured.real.scale.toInt()), // i18n-verbatim: números e unidade
        )
    }
}

@Composable
private fun PanelSection(titleRes: Int, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(CzTokens.surface, RoundedCornerShape(14.dp))
            .border(1.dp, CzTokens.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(titleRes).uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            color = CzTokens.textTertiary,
        )
        content()
    }
}

@Composable
private fun PanelValue(labelRes: Int, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CzTokens.textPrimary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CzTokens.textSecondary)
    }
}

@Composable
private fun PanelSlider(
    labelRes: Int,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    accent: Color,
    detail: ((Double) -> String)? = null,
    onChange: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(labelRes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CzTokens.textPrimary, modifier = Modifier.weight(1f))
            Text(
                text = detail?.invoke(value) ?: (value * 100).toInt().toString(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = CzTokens.textTertiary,
            )
        }
        CzSlider(
            accent = accent,
            value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.height(28.dp),
        )
    }
}

/** Dois botões, nunca um Stepper do sistema: ele ignorava toques em cima de um grafo de áudio vivo. */
@Composable
private fun PanelCounter(
    labelRes: Int,
    value: Int,
    range: IntRange,
    accent: Color,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    val label = stringResource(labelRes)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$label: ${format(value)}" }, // i18n-verbatim: rótulo + valor
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CzTokens.textPrimary, modifier = Modifier.weight(1f))
        CounterButton(Icons.Filled.Remove, enabled = value > range.first, accent) { onChange(maxOf(range.first, value - 1)) }
        Text(
            text = format(value),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = CzTokens.textPrimary,
            modifier = Modifier.width(58.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        CounterButton(Icons.Filled.Add, enabled = value < range.last, accent) { onChange(minOf(range.last, value + 1)) }
    }
}

@Composable
private fun CounterButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) accent else CzTokens.textTertiary.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
    }
}

/** Os toques do instrumento, pela View: o papel do `UIImpactFeedbackGenerator`. */
private class ViewHaptics(private val view: View) : CordasHaptics {
    override fun strum(strings: Int) =
        performHaptic(if (strings > 2) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK)
    override fun chord() = performHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
    override fun tchac() = performHaptic(HapticFeedbackConstants.LONG_PRESS)
    override fun light() = performHaptic(HapticFeedbackConstants.CLOCK_TICK)
    override fun medium() = performHaptic(HapticFeedbackConstants.CONTEXT_CLICK)

    private fun performHaptic(constant: Int) {
        view.performHapticFeedback(constant)
    }
}
