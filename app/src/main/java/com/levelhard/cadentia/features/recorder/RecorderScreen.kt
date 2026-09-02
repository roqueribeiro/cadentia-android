package com.levelhard.cadentia.features.recorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.RecorderHistory
import com.levelhard.cadentia.kit.RecorderProject
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * O Gravador — port do `RecorderView.swift`: uma janela de arranjo, não uma
 * lista de takes. Linha do tempo com playhead que se arrasta, clipes com
 * forma de onda, alças de aparo, dividir no playhead, duplicar, fades,
 * volume e pan por trilha, undo sobre tudo. Gravar entra no playhead, na
 * trilha armada, com count-in — overdub começa no meio da música.
 *
 * O chip de Isolamento de Voz do iOS NÃO existe aqui: aquilo abre um painel
 * do sistema da Apple. O modo estúdio (VOICE_COMMUNICATION, com AEC) é o
 * equivalente Android do Voice Processing.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun RecorderScreen(store: SettingsStore) {
    val accent = CzTokens.recorderCyan
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val engine = remember { RecorderEngine(context.applicationContext) }
    val waveforms = remember { WaveformStore(engine) }
    val history = remember { RecorderHistory() }

    var project by remember { mutableStateOf(RecorderProject()) }
    var revision by remember { mutableIntStateOf(0) }
    var historyStamp by remember { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var playhead by remember { mutableDoubleStateOf(0.0) }
    var currentTakeName by remember { mutableStateOf<String?>(null) }
    var recordStartedAt by remember { mutableDoubleStateOf(0.0) }

    var layout by remember { mutableStateOf(TimelineLayout(pixelsPerSecond = 42.0, offset = 0.0)) }
    var selectedClipId by remember { mutableStateOf<String?>(null) }
    var draggingClipId by remember { mutableStateOf<String?>(null) }
    var dragTimeDelta by remember { mutableDoubleStateOf(0.0) }
    var dragTrackDelta by remember { mutableIntStateOf(0) }

    val peaks = remember { mutableStateMapOf<String, WaveformPeaks>() }
    var settingsTrackId by remember { mutableStateOf<String?>(null) }
    var showClipEditor by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var studioMode by remember { mutableStateOf(true) }
    var snapToGrid by remember { mutableStateOf(true) }

    fun loadPeaks() {
        val wanted = project.referencedFiles() - peaks.keys
        if (wanted.isEmpty()) return
        scope.launch {
            for (fileName in wanted) {
                waveforms.peaks(fileName)?.let { peaks[fileName] = it }
            }
        }
    }

    fun saveProject() {
        project.sanitize()
        runCatching { engine.projectFile().writeText(project.serialized()) }
        revision++
        loadPeaks()
    }

    fun stopEverythingKeepTake(): Boolean {
        // true quando havia gravação em curso (o take precisa ser fechado).
        val wasRecording = isRecording
        engine.stopAll()
        isPlaying = false
        isRecording = false
        playhead = engine.currentTime
        return wasRecording
    }

    fun finishRecording() {
        engine.finishTake()
        engine.stopAll()
        isRecording = false
        isPlaying = false

        val takeName = currentTakeName ?: return
        currentTakeName = null
        val duration = engine.duration(takeName)
        // Take mais curto que um piscar é toque acidental, não performance.
        if (duration <= 0.05) {
            engine.takeFile(takeName).delete()
            playhead = engine.currentTime
            return
        }
        history.record(project)
        historyStamp++
        project.addClip(
            RecorderProject.Clip(
                fileName = takeName, start = recordStartedAt,
                duration = duration, sourceDuration = duration,
            ),
            toTrack = project.tracks.firstOrNull { it.armed }?.id
                ?: project.tracks.firstOrNull()?.id ?: return,
        )
        saveProject()
        playhead = recordStartedAt + duration
    }

    fun stopEverything() {
        if (isRecording) {
            finishRecording()
        } else {
            stopEverythingKeepTake()
        }
    }

    fun mutateProject(recordHistory: Boolean = true, apply: (RecorderProject) -> Unit) {
        if (recordHistory) {
            history.record(project)
            historyStamp++
        }
        apply(project)
        saveProject()
    }

    val recordTrackId = project.tracks.firstOrNull { it.armed }?.id ?: project.tracks.firstOrNull()?.id

    fun beginRecording() {
        val trackId = recordTrackId ?: return
        engine.studioMode = studioMode
        recordStartedAt = playhead
        val takeName = engine.start(
            project, from = playhead,
            record = RecorderEngine.RecordRequest(trackId, playhead),
        )
        if (takeName != null) {
            currentTakeName = takeName
            isRecording = true
            isPlaying = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) beginRecording() }

    fun toggleRecording() {
        if (isRecording) {
            finishRecording()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) beginRecording() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun togglePlayback() {
        if (isPlaying) {
            stopEverything()
        } else {
            if (engine.start(project, from = playhead, record = null) == null && project.duration <= 0) return
            isPlaying = true
        }
    }

    fun snap(time: Double): Double {
        if (!snapToGrid) return time
        // Semicolcheias no andamento do projeto.
        val grid = 60.0 / maxOf(project.bpm, 1) / 4
        return kotlin.math.round(time / grid) * grid
    }

    fun exportMix() {
        if (isExporting) return
        stopEverything()
        isExporting = true
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                engine.mixdown(context, project, enhance = true)
            }
            isExporting = false
            if (file != null) shareFile(context, file)
        }
    }

    // Projeto do disco na primeira entrada; trilha 1 se estiver vazio.
    LaunchedEffect(Unit) {
        runCatching { engine.projectFile().readText() }.getOrNull()?.let {
            project = RecorderProject.load(it)
        }
        if (project.tracks.isEmpty()) {
            project.addTrack(context.getString(R.string.cadentia_recorder_track_name) + " 1")
        }
        revision++
        loadPeaks()
    }

    // Ticker do playhead enquanto o transporte roda.
    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            delay(50)
            if (!isPlaying) break
            playhead = engine.currentTime
            // Mantém o playhead na tela.
            val rightEdge = layout.time(260.0)
            if (playhead > rightEdge) {
                layout = layout.copy(offset = playhead - 200.0 / layout.pixelsPerSecond)
            } else if (playhead < layout.offset) {
                layout = layout.copy(offset = maxOf(0.0, playhead - 1))
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { engine.shutdown() }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 10.dp),
        ) {
            @Suppress("UNUSED_EXPRESSION") revision

            // ---- barra de opções ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                OptionChip(
                    text = stringResource(R.string.cadentia_recorder_studio_mode),
                    icon = Icons.Filled.Mic,
                    active = studioMode,
                    accent = accent,
                    tag = "recorder.studioMode",
                ) {
                    studioMode = !studioMode
                    engine.studioMode = studioMode
                }
                OptionChip(
                    text = stringResource(R.string.cadentia_recorder_metronome),
                    icon = Icons.Filled.Timer,
                    active = project.metronomeEnabled,
                    accent = accent,
                ) { mutateProject(recordHistory = false) { it.metronomeEnabled = !it.metronomeEnabled } }
                OptionChip(
                    text = stringResource(R.string.cadentia_recorder_snap),
                    icon = Icons.Filled.Straighten,
                    active = snapToGrid,
                    accent = accent,
                ) { snapToGrid = !snapToGrid }

                // BPM ±5
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .background(CzTokens.surface, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = null, // PENDÊNCIA a11y: sem chave "diminuir" no catálogo
                        tint = CzTokens.textSecondary,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(14.dp)
                            .clickable { mutateProject(recordHistory = false) { it.bpm = (it.bpm - 5).coerceIn(40, 240) } },
                    )
                    Text(
                        text = "${project.bpm} " + stringResource(R.string.tablature_bpm),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CzTokens.textPrimary,
                    )
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null, // PENDÊNCIA a11y: sem chave "aumentar" no catálogo
                        tint = CzTokens.textSecondary,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(14.dp)
                            .clickable { mutateProject(recordHistory = false) { it.bpm = (it.bpm + 5).coerceIn(40, 240) } },
                    )
                }

                Box {
                    OptionChip(
                        text = stringResource(R.string.cadentia_recorder_export_mix),
                        icon = Icons.Filled.IosShare,
                        active = false,
                        accent = accent,
                        enabled = project.tracks.isNotEmpty() && !isExporting,
                    ) { exportMix() }
                    if (isExporting) {
                        CircularProgressIndicator(
                            color = accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(16.dp)
                                .align(Alignment.Center),
                        )
                    }
                }
            }

            // ---- transporte ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RoundIcon(Icons.Filled.SkipPrevious, size = 38.dp) {
                    stopEverything()
                    playhead = 0.0
                    engine.seek(0.0)
                }
                RoundIcon(
                    if (isPlaying && !isRecording) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    size = 46.dp,
                    enabled = project.tracks.isNotEmpty() && !isRecording,
                    tag = "recorder.play",
                ) { togglePlayback() }

                // O botão de gravar: anel danger, quadrado quando gravando.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(58.dp)
                        .background(Color(0xFF29292F), CircleShape)
                        .border(2.dp, CzTokens.danger.copy(alpha = if (isRecording) 1f else 0.6f), CircleShape)
                        .clickable { toggleRecording() }
                        .testTag("recorder.record"),
                ) {
                    if (isRecording) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(CzTokens.danger, RoundedCornerShape(4.dp)),
                        )
                    } else {
                        Box(
                            Modifier
                                .size(42.dp)
                                .background(CzTokens.danger, CircleShape),
                        )
                    }
                }

                Column {
                    Text(
                        text = timeLabel(playhead),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        color = if (isRecording) CzTokens.danger else CzTokens.textPrimary,
                    )
                    Text(
                        text = timeLabel(project.duration),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        color = CzTokens.textTertiary,
                    )
                }

                Spacer(Modifier.weight(1f))

                @Suppress("UNUSED_EXPRESSION") historyStamp
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = null, // PENDÊNCIA a11y: sem chave "desfazer" no catálogo
                    tint = if (history.canUndo) CzTokens.textSecondary else CzTokens.textTertiary.copy(alpha = 0.4f),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(32.dp)
                        .clickable(enabled = history.canUndo) {
                            history.undo(project)?.let {
                                stopEverything()
                                project = it
                                historyStamp++
                                saveProject()
                            }
                        }
                        .padding(6.dp)
                        .testTag("recorder.undo"),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = null, // PENDÊNCIA a11y: sem chave "refazer" no catálogo
                    tint = if (history.canRedo) CzTokens.textSecondary else CzTokens.textTertiary.copy(alpha = 0.4f),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(32.dp)
                        .clickable(enabled = history.canRedo) {
                            history.redo(project)?.let {
                                stopEverything()
                                project = it
                                historyStamp++
                                saveProject()
                            }
                        }
                        .padding(6.dp),
                )
            }

            if (project.tracks.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(42.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.cadentia_recorder_empty_hint),
                        fontSize = 14.sp,
                        color = CzTokens.textSecondary,
                    )
                }
            } else {
                // ---- linha do tempo ----
                TimelineArea(
                    project = project,
                    layout = layout,
                    playhead = playhead,
                    selectedClipId = selectedClipId,
                    draggingClipId = draggingClipId,
                    dragTimeDelta = dragTimeDelta,
                    peaks = peaks,
                    recordTrackId = recordTrackId,
                    revision = revision,
                    onLayout = { layout = it },
                    onScrub = {
                        if (isPlaying || isRecording) stopEverything()
                        playhead = maxOf(0.0, it)
                        engine.seek(playhead)
                    },
                    onSelect = { selectedClipId = if (selectedClipId == it) null else it },
                    onDragState = { id, timeDelta, trackDelta ->
                        draggingClipId = id
                        dragTimeDelta = timeDelta
                        dragTrackDelta = trackDelta
                    },
                    onDragEnd = { clip, trackId ->
                        val timeDelta = dragTimeDelta
                        val trackDelta = dragTrackDelta
                        draggingClipId = null
                        dragTimeDelta = 0.0
                        dragTrackDelta = 0
                        if (kotlin.math.abs(timeDelta) > 0.001 || trackDelta != 0) {
                            mutateProject { p ->
                                p.updateClip(clip.id) { it.start = snap(maxOf(0.0, clip.start + timeDelta)) }
                                if (trackDelta != 0) {
                                    val index = p.tracks.indexOfFirst { it.id == trackId }
                                    if (index >= 0) {
                                        val target = (index + trackDelta).coerceIn(0, p.tracks.size - 1)
                                        if (target != index) p.moveClip(clip.id, p.tracks[target].id)
                                    }
                                }
                            }
                        }
                    },
                    onTrim = { clip, edge, delta ->
                        mutateProject { p ->
                            p.updateClip(clip.id) { target ->
                                when (edge) {
                                    TrimEdge.Start -> {
                                        // Aparar a cabeça move posição e offset
                                        // juntos: o áudio não desliza debaixo.
                                        val limited = maxOf(-target.trimStart, minOf(delta, target.duration - 0.05))
                                        target.start += limited
                                        target.trimStart += limited
                                        target.duration -= limited
                                    }
                                    TrimEdge.End -> {
                                        val available = target.sourceDuration - target.trimStart
                                        target.duration = minOf(available, maxOf(0.05, target.duration + delta))
                                    }
                                }
                            }
                        }
                    },
                    onArm = { id ->
                        mutateProject(recordHistory = false) { p ->
                            for (track in p.tracks) track.armed = track.id == id
                        }
                    },
                    onMute = { id -> mutateProject(recordHistory = false) { p -> p.updateTrack(id) { it.muted = !it.muted } } },
                    onSolo = { id -> mutateProject(recordHistory = false) { p -> p.updateTrack(id) { it.soloed = !it.soloed } } },
                    onOpenSettings = {
                        history.record(project)
                        historyStamp++
                        settingsTrackId = it
                    },
                    onAddTrack = {
                        mutateProject { p ->
                            p.addTrack(context.getString(R.string.cadentia_recorder_track_name) + " ${p.tracks.size + 1}")
                        }
                    },
                )

                // ---- barra de edição do clipe ----
                val selected = selectedClipId?.let { project.clip(it) }
                if (selected != null) {
                    val (_, clip) = selected
                    val canSplit = playhead > clip.start + 0.02 && playhead < clip.end - 0.02
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ActionChip(
                            text = stringResource(R.string.cadentia_recorder_split),
                            icon = Icons.Filled.ContentCut,
                            enabled = canSplit,
                            tag = "recorder.split",
                        ) { mutateProject { it.splitClip(clip.id, playhead) } }
                        ActionChip(
                            text = stringResource(R.string.cadentia_recorder_duplicate),
                            icon = Icons.Filled.ContentCopy,
                        ) {
                            mutateProject { selectedClipId = it.duplicateClip(clip.id) ?: selectedClipId }
                        }
                        ActionChip(
                            text = stringResource(R.string.cadentia_recorder_adjust),
                            icon = Icons.Filled.Tune,
                        ) {
                            // Um snapshot quando o painel abre, não por tick de
                            // slider — um arrasto só não enterra o undo.
                            history.record(project)
                            historyStamp++
                            showClipEditor = true
                        }
                        ActionChip(
                            text = null,
                            icon = Icons.Filled.Delete,
                            destructive = true,
                            tag = "recorder.deleteClip",
                        ) {
                            mutateProject { it.removeClip(clip.id) }
                            selectedClipId = null
                        }
                    }
                }
            }
        }
    }

    // ---- sheets ----
    settingsTrackId?.let { trackId ->
        project.track(trackId)?.let { track ->
            TrackSettingsSheet(
                track = track,
                revision = revision,
                onDismiss = { settingsTrackId = null },
                onChange = { apply ->
                    mutateProject(recordHistory = false) { it.updateTrack(trackId, apply) }
                },
                onDelete = {
                    settingsTrackId = null
                    stopEverything()
                    mutateProject { it.removeTrack(trackId) }
                    selectedClipId = null
                    engine.collectGarbage(project.referencedFiles())
                },
            )
        }
    }
    if (showClipEditor) {
        selectedClipId?.let { project.clip(it) }?.let { (_, clip) ->
            ClipSettingsSheet(
                clip = clip,
                revision = revision,
                onDismiss = { showClipEditor = false },
                onChange = { apply ->
                    mutateProject(recordHistory = false) { it.updateClip(clip.id, apply) }
                },
            )
        }
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name))
}

enum class TrimEdge { Start, End }

// ---- linha do tempo ----

@Composable
private fun TimelineArea(
    project: RecorderProject,
    layout: TimelineLayout,
    playhead: Double,
    selectedClipId: String?,
    draggingClipId: String?,
    dragTimeDelta: Double,
    peaks: Map<String, WaveformPeaks>,
    recordTrackId: String?,
    revision: Int,
    onLayout: (TimelineLayout) -> Unit,
    onScrub: (Double) -> Unit,
    onSelect: (String) -> Unit,
    onDragState: (String?, Double, Int) -> Unit,
    onDragEnd: (RecorderProject.Clip, String) -> Unit,
    onTrim: (RecorderProject.Clip, TrimEdge, Double) -> Unit,
    onArm: (String) -> Unit,
    onMute: (String) -> Unit,
    onSolo: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    onAddTrack: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    val density = LocalDensity.current
    fun pxToDp(px: Float): Double = (px / density.density).toDouble()
    // Gestos vivem em pointerInput(Unit): a closure precisa enxergar o
    // layout ATUAL, não o da primeira composição.
    val liveLayout by androidx.compose.runtime.rememberUpdatedState(layout)

    val lanesHeight = TimelineLayout.rulerHeight + 4.dp +
        (TimelineLayout.laneHeight + 4.dp) * project.tracks.size

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // Cabeçalhos.
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.width(TimelineLayout.headerWidth),
        ) {
            Spacer(Modifier.height(TimelineLayout.rulerHeight))
            for (track in project.tracks) {
                TrackHeaderView(
                    track = track,
                    isRecordTarget = track.id == recordTrackId,
                    onArm = { onArm(track.id) },
                    onMute = { onMute(track.id) },
                    onSolo = { onSolo(track.id) },
                    onOpenSettings = { onOpenSettings(track.id) },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .width(TimelineLayout.headerWidth)
                    .height(30.dp)
                    .background(CzTokens.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onAddTrack)
                    .padding(horizontal = 8.dp)
                    .testTag("recorder.addTrack"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = CzTokens.recorderCyan,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = stringResource(R.string.cadentia_recorder_add_track),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = CzTokens.recorderCyan,
                )
            }
        }

        // Régua + lanes + playhead.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(lanesHeight)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TimelineRuler(
                    layout = layout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset -> onScrub(liveLayout.time(pxToDp(offset.x))) },
                            ) { change, _ ->
                                change.consume()
                                onScrub(liveLayout.time(pxToDp(change.position.x)))
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset -> onScrub(liveLayout.time(pxToDp(offset.x))) }
                        },
                )
                for (track in project.tracks) {
                    Lane(
                        track = track,
                        layout = layout,
                        selectedClipId = selectedClipId,
                        draggingClipId = draggingClipId,
                        dragTimeDelta = dragTimeDelta,
                        peaks = peaks,
                        onSelect = onSelect,
                        onDragState = onDragState,
                        onDragEnd = onDragEnd,
                        onTrim = onTrim,
                    )
                }
            }

            // Pan + zoom no fundo da área.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val pps = (liveLayout.pixelsPerSecond * zoom)
                                .coerceIn(TimelineLayout.MIN_ZOOM, TimelineLayout.MAX_ZOOM)
                            val offset = maxOf(0.0, liveLayout.offset - pxToDp(pan.x) / pps)
                            onLayout(TimelineLayout(pixelsPerSecond = pps, offset = offset))
                        }
                    },
            )

            // Playhead (o clip do Box pai corta o que sair da janela).
            val xDp = layout.x(playhead)
            if (xDp >= -2) {
                Box(
                    Modifier
                        .offset(x = xDp.dp, y = 0.dp)
                        .width(2.dp)
                        .height(lanesHeight)
                        .background(CzTokens.danger),
                )
            }
        }
    }
}

@Composable
private fun Lane(
    track: RecorderProject.Track,
    layout: TimelineLayout,
    selectedClipId: String?,
    draggingClipId: String?,
    dragTimeDelta: Double,
    peaks: Map<String, WaveformPeaks>,
    onSelect: (String) -> Unit,
    onDragState: (String?, Double, Int) -> Unit,
    onDragEnd: (RecorderProject.Clip, String) -> Unit,
    onTrim: (RecorderProject.Clip, TrimEdge, Double) -> Unit,
) {
    val density = LocalDensity.current
    // O zoom pode mudar entre a montagem do gesto e o arrasto: a conversão
    // px→segundos tem que ler o valor vivo.
    val livePps by androidx.compose.runtime.rememberUpdatedState(layout.pixelsPerSecond)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineLayout.laneHeight)
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
    ) {
        for (clip in track.clips) {
            val isDragging = draggingClipId == clip.id
            val start = clip.start + (if (isDragging) dragTimeDelta else 0.0)
            val xDp = layout.x(start)
            Box(
                Modifier
                    .offset(x = xDp.dp, y = 5.dp)
                    .pointerInput(clip.id) {
                        detectTapGestures { onSelect(clip.id) }
                    }
                    .pointerInput(clip.id) {
                        var timeDelta = 0.0
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = {
                                timeDelta = 0.0
                                totalY = 0f
                                onDragState(clip.id, 0.0, 0)
                            },
                            onDragEnd = { onDragEnd(clip, track.id) },
                            onDragCancel = { onDragState(null, 0.0, 0) },
                        ) { change, dragAmount ->
                            change.consume()
                            timeDelta += (dragAmount.x / density.density) / livePps
                            totalY += dragAmount.y
                            val laneStep = with(density) { (TimelineLayout.laneHeight + 4.dp).toPx() }
                            onDragState(clip.id, timeDelta, kotlin.math.round(totalY / laneStep).toInt())
                        }
                    },
            ) {
                ClipView(
                    clip = clip,
                    color = TrackPalette.color(track.colorIndex),
                    isSelected = selectedClipId == clip.id,
                    layout = layout,
                    peaks = peaks[clip.fileName],
                )
            }

            // Alças de aparo do clipe selecionado.
            if (selectedClipId == clip.id && !isDragging) {
                TrimHandle(
                    color = TrackPalette.color(track.colorIndex),
                    xDp = layout.x(start) - 5,
                ) { deltaPx -> onTrim(clip, TrimEdge.Start, (deltaPx / density.density) / livePps) }
                TrimHandle(
                    color = TrackPalette.color(track.colorIndex),
                    xDp = layout.x(start + clip.duration) - 5,
                ) { deltaPx -> onTrim(clip, TrimEdge.End, (deltaPx / density.density) / livePps) }
            }
        }
    }
}

/** Alça de aparo: acumula o arrasto e aplica no fim, como o iOS. */
@Composable
private fun TrimHandle(color: Color, xDp: Double, onApply: (Float) -> Unit) {
    Box(
        Modifier
            .offset(x = xDp.dp, y = 5.dp)
            .width(10.dp)
            .height(TimelineLayout.laneHeight - 10.dp)
            .background(color, RoundedCornerShape(3.dp))
            .pointerInput(Unit) {
                var total = 0f
                detectDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { onApply(total) },
                ) { change, dragAmount ->
                    change.consume()
                    total += dragAmount.x
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(2.dp)
                .height(14.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(1.dp)),
        )
    }
}

// ---- chips e botões ----

@Composable
private fun OptionChip(
    text: String,
    icon: ImageVector,
    active: Boolean,
    accent: Color,
    enabled: Boolean = true,
    tag: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .background(if (active) accent.copy(alpha = 0.18f) else CzTokens.surface, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        val tint = if (active) accent else CzTokens.textSecondary
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = tint,
        )
    }
}

@Composable
private fun ActionChip(
    text: String?,
    icon: ImageVector,
    destructive: Boolean = false,
    enabled: Boolean = true,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> CzTokens.textTertiary.copy(alpha = 0.45f)
        destructive -> CzTokens.danger
        else -> CzTokens.textPrimary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .background(CzTokens.surface, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (text == null) 13.dp else 11.dp, vertical = 8.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        if (text != null) {
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = tint,
            )
        }
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    tag: String? = null,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(CzTokens.surface, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) CzTokens.textPrimary else CzTokens.textTertiary,
            modifier = Modifier.size(size / 2.4f),
        )
    }
}

// ---- sheets ----

/** Inspetor da trilha: nome, níveis, cor e remoção. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSettingsSheet(
    track: RecorderProject.Track,
    revision: Int,
    onDismiss: () -> Unit,
    onChange: ((RecorderProject.Track) -> Unit) -> Unit,
    onDelete: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.cadentia_recorder_track_settings),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            OutlinedTextField(
                value = track.name,
                onValueChange = { name -> onChange { it.name = name } },
                label = { Text(stringResource(R.string.cadentia_recorder_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CzTokens.textPrimary,
                    unfocusedTextColor = CzTokens.textPrimary,
                    focusedBorderColor = TrackPalette.color(track.colorIndex),
                    unfocusedBorderColor = CzTokens.hairline,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            LabelledSlider(
                label = stringResource(R.string.tablature_tracks_volume),
                value = track.volume.toFloat(),
                range = 0f..1.5f,
                accent = TrackPalette.color(track.colorIndex),
            ) { value -> onChange { it.volume = value.toDouble() } }
            LabelledSlider(
                label = stringResource(R.string.cadentia_recorder_pan),
                value = track.pan.toFloat(),
                range = -1f..1f,
                accent = TrackPalette.color(track.colorIndex),
            ) { value -> onChange { it.pan = value.toDouble() } }

            Text(
                text = stringResource(R.string.cadentia_recorder_color),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textTertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (index in 0 until RecorderProject.COLOR_COUNT) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(TrackPalette.color(index), CircleShape)
                            .border(
                                width = 2.dp,
                                color = if (track.colorIndex == index) Color.White else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable { onChange { it.colorIndex = index } },
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(CzTokens.danger.copy(alpha = 0.16f), RoundedCornerShape(50))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = CzTokens.danger,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.cadentia_recorder_delete_track),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CzTokens.danger,
                )
            }
        }
    }
}

/** Inspetor do clipe: ganho e os dois fades. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipSettingsSheet(
    clip: RecorderProject.Clip,
    revision: Int,
    onDismiss: () -> Unit,
    onChange: ((RecorderProject.Clip) -> Unit) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.cadentia_recorder_clip_settings),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            LabelledSlider(
                label = stringResource(R.string.cadentia_recorder_gain),
                value = clip.gain.toFloat(),
                range = 0f..2f,
                accent = CzTokens.gold,
            ) { value -> onChange { it.gain = value.toDouble() } }
            val fadeCeiling = maxOf(0.1, clip.duration / 2).toFloat()
            LabelledSlider(
                label = stringResource(R.string.cadentia_recorder_fade_in),
                value = clip.fadeIn.toFloat(),
                range = 0f..fadeCeiling,
                accent = CzTokens.gold,
            ) { value -> onChange { it.fadeIn = value.toDouble() } }
            LabelledSlider(
                label = stringResource(R.string.cadentia_recorder_fade_out),
                value = clip.fadeOut.toFloat(),
                range = 0f..fadeCeiling,
                accent = CzTokens.gold,
            ) { value -> onChange { it.fadeOut = value.toDouble() } }
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValue: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = CzTokens.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format(java.util.Locale.ROOT, "%.2f", value),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textTertiary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = CzTokens.surface,
            ),
        )
    }
}
