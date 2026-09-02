package com.levelhard.cadentia.features.tuner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.levelhard.cadentia.kit.TuningCatalog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.levelhard.cadentia.LocalQaFlags
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.InstrumentPreset
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import kotlin.math.abs

/**
 * O afinador — port do `TunerView.swift`: abre ESCUTANDO (o prompt de
 * permissão do sistema é a única tela no primeiro uso), ring gauge com a
 * nota dentro, gráfico de afinação ao vivo e análise de sessão (tee do
 * áudio + linha do tempo de pitch, teto de 60 s, resumo em folha).
 */
@Composable
fun TunerScreen(store: SettingsStore) {
    val context = LocalContext.current
    val view = LocalView.current
    val qa = LocalQaFlags.current
    val vm: TunerViewModel = viewModel()
    val state by vm.state.collectAsState()
    val settings by store.settings.collectAsState()

    val referenceA = settings.tuner.referenceA
    val instrument = InstrumentPreset.find(settings.tuner.lastInstrument)
    val tuningRows = rememberTuningRows()
    val instrumentRow = tuningRows.firstOrNull { it.id == instrument.id }
    var showTunings by remember { mutableStateOf(false) }

    // Derivados de exibição (a mesma cadeia do iOS).
    val detectedNote = state.heldFrequency?.let { MusicNotes.noteFromFrequency(it, referenceA) }
    val targetString = state.heldFrequency?.let { instrument.nearestString(it, referenceA) }
    val displayCents: Int = state.heldFrequency?.let { hz ->
        targetString?.let { MusicNotes.centsOff(detected = hz, target = it.frequency) }
            ?: detectedNote?.cents
    } ?: 0
    val noteDisplay = detectedNote?.let { targetString?.note?.name ?: it.name } ?: "—"
    val octaveDisplay = detectedNote?.let { (targetString?.note?.octave ?: it.octave).toString() } ?: ""
    val isTuned = state.heldFrequency != null && abs(displayCents) <= 5

    // Permissão sem tela de CTA: pede e já começa a escutar.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.activate() else vm.permissionDenied() }

    LaunchedEffect(Unit) {
        if (qa.tunerSilent) {
            // A flag quer dizer "não ligue o microfone", não "finja que está
            // carregando": sem isto nenhum teste alcançava o seletor (1.16).
            vm.activateSilentlyForQa()
            return@LaunchedEffect
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.activate() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // A linha do tempo gravada lê as configurações VIVAS (não as da última
    // recomposição) — o mesmo settingsProvider do iOS.
    LaunchedEffect(store) {
        vm.settingsProvider = {
            val tuner = store.settings.value.tuner
            tuner.referenceA to InstrumentPreset.find(tuner.lastInstrument)
        }
    }
    DisposableEffect(Unit) {
        onDispose { vm.deactivate() }
    }

    // Um toque suave de sucesso quando o ponteiro assenta no verde.
    var wasTuned by remember { mutableStateOf(false) }
    LaunchedEffect(isTuned) {
        if (isTuned && !wasTuned) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        wasTuned = isTuned
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = CzTokens.tunerGreen)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state.status) {
                    TunerViewModel.MicStatus.Starting -> Gauge(
                        displayCents, noteDisplay, octaveDisplay, state, isTuned,
                    )
                    TunerViewModel.MicStatus.Denied,
                    TunerViewModel.MicStatus.Error,
                    -> DeniedState()
                    TunerViewModel.MicStatus.Active -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TuningButton(
                                label = instrumentRow?.compactLabel ?: "",
                                modifier = Modifier.weight(1f, fill = false),
                            ) { showTunings = true }
                            Spacer(Modifier.weight(1f))
                            ReferenceControl(referenceA) { delta ->
                                store.update {
                                    it.tuner.referenceA =
                                        (it.tuner.referenceA + delta).coerceIn(415.0, 466.0)
                                }
                            }
                        }
                        StringChips(
                            instrument = instrument,
                            targetNote = targetString?.note,
                            isTuned = isTuned,
                        )
                        Gauge(displayCents, noteDisplay, octaveDisplay, state, isTuned)
                        TuningGraphView(
                            cents = displayCents,
                            active = state.heldFrequency != null,
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                        )
                        StatusPills(state, isTuned, displayCents)
                        AnalysisControls(
                            state = state,
                            onStart = { vm.startRecording(context.cacheDir) },
                            onStop = { vm.stopRecording() },
                        )
                    }
                }
            }
        }
    }

    if (showTunings) {
        TuningPickerSheet(
            rows = tuningRows,
            selectedId = instrument.id,
            recentIds = settings.tuner.recentInstruments,
            onPick = { preset ->
                // Guarda a escolha e empurra para os recentes — é o que faz a
                // folha abrir já mostrando a afinação de ontem no topo.
                store.update {
                    it.tuner.lastInstrument = preset.id
                    it.tuner.recentInstruments = TuningCatalog.pushRecent(preset.id, it.tuner.recentInstruments)
                }
            },
            onDismiss = { showTunings = false },
        )
    }

    if (state.showSessionModal) {
        state.session?.let { session ->
            TunerSessionSheet(
                session = session,
                accent = CzTokens.tunerGreen,
                onDismiss = { vm.dismissSessionModal() },
            )
        }
    }
}

/**
 * O botão de análise — port do `analysisButton` do iOS, com o mesmo texto
 * honesto: isto não é um gravador (o app tem uma aba para isso), é medir um
 * trecho de estudo — que nota segurou, quanto tempo afinado, quanto desviou.
 */
@Composable
private fun AnalysisControls(
    state: TunerViewModel.State,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (state.isRecording) {
            Surface(
                onClick = onStop,
                shape = CircleShape,
                color = CzTokens.tunerGreen,
                contentColor = Color.Black,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.cadentia_tuner_analysis_stop) +
                            " (${state.recordingElapsedLabel})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Surface(
                onClick = onStart,
                shape = CircleShape,
                color = CzTokens.surface,
                contentColor = CzTokens.textPrimary,
                border = androidx.compose.foundation.BorderStroke(1.dp, CzTokens.hairline),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = null,
                        tint = CzTokens.tunerGreen,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.cadentia_tuner_analysis_start),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = stringResource(R.string.cadentia_tuner_analysis_hint),
                fontSize = 11.sp,
                color = CzTokens.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun Gauge(
    cents: Int,
    note: String,
    octave: String,
    state: TunerViewModel.State,
    isTuned: Boolean,
) {
    TunerRingGauge(
        cents = cents,
        note = note,
        octave = octave,
        frequencyLabel = state.heldFrequency?.let { "%.1f Hz".format(it) },
        active = state.heldFrequency != null,
        isTuned = isTuned,
        accent = CzTokens.tunerGreen,
        modifier = Modifier.fillMaxWidth().height(320.dp),
    )
}

@Composable
private fun DeniedState() {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 60.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.MicOff,
            contentDescription = null,
            tint = CzTokens.danger,
            modifier = Modifier.size(52.dp),
        )
        Text(
            text = stringResource(R.string.music_tuner_denied_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = CzTokens.textPrimary,
        )
        Text(
            text = stringResource(R.string.music_tuner_denied_hint),
            fontSize = 14.sp,
            color = CzTokens.textSecondary,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }) {
            Text(
                text = stringResource(R.string.music_tuner_try_again),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.gold,
            )
        }
    }
}

/** Referência de orquestra: A4 = 415–466 Hz (persistida, paridade com o web). */
@Composable
private fun ReferenceControl(referenceA: Double, adjust: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundIconButton(Icons.Filled.Remove) { adjust(-1.0) }
        Text(
            text = "A4 ${referenceA.toInt()}", // i18n-verbatim: nota + número
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (referenceA == 440.0) CzTokens.textSecondary else CzTokens.gold,
        )
        RoundIconButton(Icons.Filled.Add) { adjust(+1.0) }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CzTokens.surface,
        contentColor = CzTokens.textSecondary,
        // Alvo mínimo de 48 dp; o círculo visível continua 32.
        modifier = Modifier.minimumInteractiveComponentSize().size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // PENDÊNCIA a11y: sem chave "diminuir/aumentar" no catálogo
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

/** Abre a folha em vez de um menu: são 49 afinações, e menu não busca. */
@Composable
private fun TuningButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CzTokens.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, CzTokens.hairline),
        modifier = modifier.semantics {
            contentDescription = label
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = stringResource(R.string.music_common_select_instrument),
                tint = CzTokens.gold,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label, // i18n-verbatim: já traduzido ("Violão · Drop C")
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = CzTokens.gold,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * A fileira de cordas da afinação, com a corda alvo acesa. Sem ela uma
 * afinação de 7 ou 8 cordas é um mostrador que diz "Si" sem dizer qual Si.
 * Cada pastilha divide a largura por igual: quatro ficam largas, oito ficam
 * estreitas em vez de escaparem da tela.
 */
@Composable
private fun StringChips(
    instrument: InstrumentPreset,
    targetNote: InstrumentPreset.StringNote?,
    isTuned: Boolean,
) {
    if (instrument.strings.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (note in instrument.strings) {
            val isTarget = targetNote == note
            val tint = if (isTuned) CzTokens.tunerGreen else CzTokens.gold
            val shape = RoundedCornerShape(CzTokens.radiusSM)
            Text(
                text = "${note.display}${note.octave}", // i18n-verbatim: nota + oitava
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isTarget) tint else CzTokens.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .background(if (isTarget) tint.copy(alpha = 0.16f) else CzTokens.surface, shape)
                    .border(1.dp, if (isTarget) tint.copy(alpha = 0.55f) else CzTokens.hairline, shape)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusPills(state: TunerViewModel.State, isTuned: Boolean, displayCents: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            isTuned -> Pill(R.string.music_tuner_tuned, Icons.Filled.CheckCircle, CzTokens.tunerGreen)
            state.heldFrequency != null -> {
                val low = displayCents < 0
                Pill(
                    if (low) R.string.music_tuner_too_low else R.string.music_tuner_too_high,
                    if (low) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    CzTokens.warnAmber,
                )
            }
            else -> Pill(R.string.music_tuner_play_note, Icons.Filled.GraphicEq, CzTokens.textTertiary)
        }
        if (state.isWeakSignal) {
            Pill(R.string.music_tuner_weak_signal, Icons.Filled.NetworkCell, CzTokens.textTertiary)
        }
    }
}

@Composable
private fun Pill(textRes: Int, icon: ImageVector, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = stringResource(textRes),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
