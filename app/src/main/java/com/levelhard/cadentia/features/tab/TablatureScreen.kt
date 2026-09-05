package com.levelhard.cadentia.features.tab

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.levelhard.cadentia.LocalQaFlags
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.RostabParser
import com.levelhard.cadentia.kit.Tablature
import com.levelhard.cadentia.kit.addChordMark
import com.levelhard.cadentia.kit.addMeasure
import com.levelhard.cadentia.kit.addRepeatBlock
import com.levelhard.cadentia.kit.addTrack
import com.levelhard.cadentia.kit.build
import com.levelhard.cadentia.kit.clearFret
import com.levelhard.cadentia.kit.insertChord
import com.levelhard.cadentia.kit.locate
import com.levelhard.cadentia.kit.removeMeasure
import com.levelhard.cadentia.kit.removeRepeatBlock
import com.levelhard.cadentia.kit.removeTrack
import com.levelhard.cadentia.kit.setDuration
import com.levelhard.cadentia.kit.setFret
import com.levelhard.cadentia.kit.setMeasureRepeats
import com.levelhard.cadentia.kit.setTrackKit
import com.levelhard.cadentia.kit.setTrackVoice
import com.levelhard.cadentia.kit.toggleArticulation
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import java.io.File

/**
 * Aba Tablaturas — port 1:1 do `TablatureScreen.swift`: abre um `.rostab`
 * escrito pelo web do RoqueOS (ou a demo embutida), o cursor cavalga a
 * tablatura enquanto cada trilha toca pela própria voz, mixer ao vivo,
 * editor com undo, catálogo de 48 bases e compartilhamento do arquivo.
 *
 * O modelo Kotlin muta no lugar (o Swift tinha struct), então cada edição
 * passa por `mutate`, que guarda o snapshot serializado para undo e sobe o
 * `revision` — é ele que força a recomposição da grade.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun TablatureScreen(store: SettingsStore) {
    val accent = CzTokens.tabIndigo
    val context = LocalContext.current
    val qa = LocalQaFlags.current

    val engine = remember { TabPlayerEngine() }
    var tab by remember { mutableStateOf<Tablature?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var loopEnabled by remember { mutableStateOf(true) }
    var countInEnabled by remember { mutableStateOf(false) }
    var currentBeat by remember { mutableIntStateOf(0) }
    var bpm by remember { mutableIntStateOf(Tablature.DEFAULT_BPM) }
    var selectedTrack by remember { mutableIntStateOf(0) }
    var showMixer by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(qa.showCatalog) }
    var showChordPicker by remember { mutableStateOf(false) }
    var blockAnchor by remember { mutableStateOf<Int?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(qa.edit) }
    var selection by remember {
        mutableStateOf(if (qa.edit) TabCellSelection(row = 2, col = 4) else null)
    }
    val undoStack = remember { ArrayDeque<String>() }
    var undoDepth by remember { mutableIntStateOf(0) }

    fun stopPlayback() {
        engine.stop()
        isPlaying = false
        currentBeat = 0
    }

    // Parado por fora (ligação, outro app, "Parar" na notificação): o botão acompanha.
    engine.sessionLabel = stringResource(R.string.tablature_title)
    engine.onSessionStopped = {
        isPlaying = false
        currentBeat = 0
    }

    fun load(newTab: Tablature) {
        stopPlayback()
        tab = newTab
        selectedTrack = 0
        selection = null
        undoStack.clear()
        undoDepth = 0
        engine.load(newTab)
        bpm = engine.bpm
        revision++
    }

    /** Toda edição passa aqui: snapshot para undo, aplica, recarrega o motor. */
    fun mutate(apply: (Tablature) -> Unit) {
        val current = tab ?: return
        stopPlayback()
        undoStack.addLast(current.serialize())
        if (undoStack.size > 24) undoStack.removeFirst()
        apply(current)
        if (selectedTrack >= current.tracks.size) selectedTrack = maxOf(0, current.tracks.size - 1)
        engine.load(current)
        undoDepth = undoStack.size
        revision++
    }

    /**
     * Modelo e undo SEM tocar no transporte, para edição que não muda o
     * plano de notas (escolher outro instrumento). `mutate` para e recarrega
     * porque assume que as notas mudaram; troca de timbre não muda.
     */
    fun mutateSound(apply: (Tablature) -> Unit) {
        val current = tab ?: return
        undoStack.addLast(current.serialize())
        if (undoStack.size > 24) undoStack.removeFirst()
        apply(current)
        undoDepth = undoStack.size
        revision++
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        stopPlayback()
        val restored = RostabParser.parse(previous)
        tab = restored
        if (selectedTrack >= restored.tracks.size) selectedTrack = maxOf(0, restored.tracks.size - 1)
        engine.load(restored)
        undoDepth = undoStack.size
        revision++
    }

    fun open(uri: Uri) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.decodeToString() ?: error("stream vazio")
            currentBeat = 0
            load(RostabParser.parse(text))
        } catch (error: Exception) {
            loadError = error.message ?: error.javaClass.simpleName
        }
    }

    fun share() {
        val current = tab ?: return
        val name = current.meta.title.ifEmpty { "tablature" }
        val file = File(context.cacheDir, "share").apply { mkdirs() }
            .resolve("$name.rostab")
        file.writeText(current.serialize())
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, name))
    }

    fun togglePlayback() {
        if (isPlaying) {
            stopPlayback()
        } else {
            engine.loopEnabled = loopEnabled
            engine.countInEnabled = countInEnabled
            engine.onBeat = { beat -> currentBeat = beat }
            engine.onFinished = {
                isPlaying = false
                currentBeat = 0
            }
            if (engine.play()) isPlaying = true
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { open(it) }
    }

    // Demo embutida na primeira entrada (paridade com o DemoTab.rostab do bundle).
    LaunchedEffect(Unit) {
        if (tab == null) {
            runCatching {
                context.assets.open("demo.rostab").use { it.readBytes() }.decodeToString()
            }.onSuccess { load(RostabParser.parse(it)) }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            engine.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .padding(top = 10.dp, bottom = 12.dp),
        ) {
            Header(
                revision = revision,
                tab = tab,
                accent = accent,
                isEditing = isEditing,
                onCatalog = { showCatalog = true },
                onShare = { share() },
                onToggleEdit = {
                    isEditing = !isEditing
                    if (!isEditing) selection = null
                },
                onOpen = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
            )

            val current = tab
            if (current != null && selectedTrack < current.tracks.size) {
                TrackChips(
                    tab = current,
                    selectedTrack = selectedTrack,
                    accent = accent,
                    revision = revision,
                    onSelect = { selectedTrack = it },
                    onMixer = { showMixer = true },
                )
                TabGridView(
                    track = current.tracks[selectedTrack],
                    chordMarks = current.chordMarks,
                    repeatBlocks = current.repeatBlocks,
                    cursorColumn = cursorColumn(current, engine, selectedTrack, currentBeat, isPlaying),
                    selection = if (isEditing) selection else null,
                    accent = accent,
                    revision = revision,
                    // Altura natural (rows × gap): com weight(1f) o cartão ocupava a
                    // tela inteira com 6 linhas no topo e um vazio escuro embaixo
                    // (QA no emulador). O Spacer abaixo é quem empurra o transporte.
                    onTapCell = if (isEditing) {
                        { row, col -> selection = TabCellSelection(row = row, col = col) }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.weight(1f))
                if (isEditing) {
                    EditBar(
                        tab = current,
                        selectedTrack = selectedTrack,
                        selection = selection,
                        blockAnchor = blockAnchor,
                        canUndo = undoDepth > 0,
                        accent = accent,
                        revision = revision,
                        onUndo = { undo() },
                        onMutate = { mutate(it) },
                        onBlockAnchor = { blockAnchor = it },
                        onChordPicker = { showChordPicker = true },
                    )
                }
                Transport(
                    accent = accent,
                    isPlaying = isPlaying,
                    countInEnabled = countInEnabled,
                    loopEnabled = loopEnabled,
                    bpm = bpm,
                    onToggle = { togglePlayback() },
                    onCountIn = {
                        countInEnabled = !countInEnabled
                        engine.countInEnabled = countInEnabled
                    },
                    onLoop = {
                        loopEnabled = !loopEnabled
                        engine.loopEnabled = loopEnabled
                    },
                    onBpm = { delta ->
                        engine.bpmOverride = (engine.bpm + delta).coerceIn(40, 240)
                        bpm = engine.bpm
                    },
                )
            } else {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            }
        }
    }

    if (showChordPicker) {
        ChordPickerSheet(accent = accent, onDismiss = { showChordPicker = false }) { chord ->
            val current = tab
            val sel = selection
            if (current != null && sel != null) {
                current.locate(selectedTrack, sel.col)?.let { (measureIdx, stepIdx) ->
                    mutate {
                        it.insertChord(chord, selectedTrack, measureIdx, startCol = stepIdx, dur = 8)
                        it.addChordMark(measureIdx, stepIdx, chord.id, chord.displayName)
                    }
                }
            }
            showChordPicker = false
        }
    }
    if (showCatalog) {
        BackingTrackCatalogSheet(accent = accent, onDismiss = { showCatalog = false }) { template, title ->
            load(template.build(title))
            showCatalog = false
        }
    }
    tab?.let { current ->
        if (showMixer) {
            TabMixerSheet(
                tab = current,
                accent = accent,
                revision = revision,
                onDismiss = { showMixer = false },
                onChange = { index, volume, muted, soloed ->
                    current.tracks[index].volume = volume
                    current.tracks[index].muted = muted
                    current.tracks[index].soloed = soloed
                    engine.updateTrack(index, volume = volume, muted = muted, soloed = soloed)
                    revision++
                },
                onVoice = { index, voiceId ->
                    mutateSound { it.setTrackVoice(index, voiceId) }
                    engine.updateTrack(index, voiceId = voiceId)
                },
                onKit = { index, kitId ->
                    mutateSound { it.setTrackKit(index, kitId) }
                    engine.updateTrack(index, kitId = kitId)
                },
            )
        }
    }
    loadError?.let { message ->
        AlertDialog(
            onDismissRequest = { loadError = null },
            title = { Text(stringResource(R.string.music_tuner_denied_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { loadError = null }) {
                    Text("OK") // i18n-verbatim: igual nos 10, como no iOS
                }
            },
        )
    }
}

/** Coluna do cursor (absoluta) na grade da trilha SELECIONADA. */
private fun cursorColumn(
    tab: Tablature,
    engine: TabPlayerEngine,
    selectedTrack: Int,
    currentBeat: Int,
    isPlaying: Boolean,
): Int? {
    if (!isPlaying) return null
    val plan = engine.planForTrack(selectedTrack) ?: return null
    val entry = plan.entryAtBeat(currentBeat) ?: return null
    return tab.tracks[selectedTrack].measureStartColumn(entry.measureIdx) + entry.stepIdx
}

// ---- cabeçalho ----

@Composable
private fun Header(
    revision: Int,
    tab: Tablature?,
    accent: Color,
    isEditing: Boolean,
    onCatalog: () -> Unit,
    onShare: () -> Unit,
    onToggleEdit: () -> Unit,
    onOpen: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = tab?.meta?.title ?: "",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = CzTokens.textPrimary,
                maxLines = 1,
            )
            val author = tab?.meta?.author.orEmpty()
            if (author.isNotEmpty()) {
                Text(
                    text = author,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = CzTokens.textTertiary,
                    maxLines = 1,
                )
            }
        }
        HeaderIcon(
            icon = Icons.Filled.GridView,
            contentDescription = stringResource(R.string.tablature_backing_tracks_title),
            active = false,
            accent = accent,
            tag = "tab.catalog",
            onClick = onCatalog,
        )
        HeaderIcon(
            icon = Icons.Filled.Share,
            contentDescription = stringResource(R.string.cadentia_a11y_share),
            active = false,
            accent = accent,
            tag = "tab.share",
            onClick = onShare,
        )
        HeaderIcon(
            icon = Icons.Filled.Edit,
            contentDescription = stringResource(R.string.cadentia_a11y_edit),
            active = isEditing,
            accent = accent,
            tag = "tab.edit",
            onClick = onToggleEdit,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .background(CzTokens.surface, RoundedCornerShape(50))
                .border(1.dp, CzTokens.hairline, RoundedCornerShape(50))
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("tab.open"),
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = CzTokens.textSecondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = stringResource(R.string.tablature_open_file),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = CzTokens.textSecondary,
            )
        }
    }
}

@Composable
private fun HeaderIcon(
    icon: ImageVector,
    contentDescription: String?,
    active: Boolean,
    accent: Color,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(36.dp)
            .background(CzTokens.surface, CircleShape)
            .border(1.dp, if (active) accent.copy(alpha = 0.6f) else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) accent else CzTokens.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ---- trilhas ----

@Composable
private fun TrackChips(
    tab: Tablature,
    selectedTrack: Int,
    accent: Color,
    revision: Int,
    onSelect: (Int) -> Unit,
    onMixer: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for ((index, track) in tab.tracks.withIndex()) {
            val selected = selectedTrack == index
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .background(
                        if (selected) accent.copy(alpha = 0.18f) else CzTokens.surface,
                        RoundedCornerShape(50),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (track.muted) CzTokens.textTertiary else accent, CircleShape),
                )
                Text(
                    text = track.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = if (selected) accent else CzTokens.textSecondary,
                )
                if (track.soloed) {
                    Text(
                        text = "S", // i18n-verbatim: sigla de solo, igual nos 10
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = CzTokens.gold,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .background(CzTokens.surface, RoundedCornerShape(50))
                .border(1.dp, CzTokens.hairline, RoundedCornerShape(50))
                .clickable(onClick = onMixer)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("tab.mixer"),
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = null,
                tint = CzTokens.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.tablature_tracks_mixer),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = CzTokens.textSecondary,
            )
        }
    }
}

// ---- barra de edição ----

@Composable
private fun EditBar(
    tab: Tablature,
    selectedTrack: Int,
    selection: TabCellSelection?,
    blockAnchor: Int?,
    canUndo: Boolean,
    accent: Color,
    revision: Int,
    onUndo: () -> Unit,
    onMutate: ((Tablature) -> Unit) -> Unit,
    onBlockAnchor: (Int?) -> Unit,
    onChordPicker: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    val track = tab.tracks[selectedTrack]
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(CzTokens.radiusMD))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(CzTokens.radiusMD))
            .padding(10.dp),
    ) {
        // Linha de estrutura: undo, compassos, trilhas, repetições.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            EditChip(icon = Icons.AutoMirrored.Filled.Undo, text = null, enabled = canUndo) { onUndo() }
            EditChip(
                icon = Icons.Filled.Add,
                text = stringResource(R.string.tablature_add_measure),
                enabled = true,
            ) { onMutate { it.addMeasure() } }
            EditChip(
                icon = Icons.Filled.Remove,
                text = stringResource(R.string.tablature_remove_measure),
                enabled = track.measures.size > 1,
            ) { onMutate { it.removeMeasure(track.measures.size - 1) } }
            AddTrackChip(onMutate)
            if (tab.tracks.size > 1) {
                EditChip(
                    icon = Icons.Filled.Delete,
                    text = stringResource(R.string.tablature_tracks_remove),
                    enabled = true,
                ) { onMutate { it.removeTrack(selectedTrack) } }
            }
            val located = selection?.let { tab.locate(selectedTrack, it.col) }
            if (located != null) {
                val (measureIdx, _) = located
                val repeats = track.measures[measureIdx].repeats
                EditChip(icon = Icons.Filled.Remove, text = null, enabled = repeats > 1) {
                    onMutate { it.setMeasureRepeats(measureIdx, repeats - 1) }
                }
                Text(
                    text = "×${maxOf(repeats, 1)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (repeats > 1) CzTokens.gold else CzTokens.textTertiary,
                )
                EditChip(icon = Icons.Filled.Add, text = null, enabled = repeats < 16) {
                    onMutate { it.setMeasureRepeats(measureIdx, maxOf(repeats, 1) + 1) }
                }
                if (track.type == "guitar" || track.type == "bass") {
                    EditChip(
                        icon = Icons.Filled.Add,
                        text = stringResource(R.string.tablature_insert_chord),
                        enabled = true,
                    ) { onChordPicker() }
                }
                // Bloco de repetição: âncora no começo, fecha ×2 no compasso
                // final, toque no selo remove.
                val existing = tab.repeatBlocks.firstOrNull {
                    measureIdx >= it.startIdx && measureIdx <= it.endIdx
                }
                when {
                    existing != null -> EditChip(icon = Icons.Filled.RepeatOn, text = null, enabled = true) {
                        onMutate { it.removeRepeatBlock(existing.id) }
                    }
                    blockAnchor != null -> EditChip(
                        icon = Icons.Filled.Repeat,
                        text = "×2", // i18n-verbatim: notação musical
                        enabled = measureIdx >= blockAnchor,
                    ) {
                        onMutate { it.addRepeatBlock(blockAnchor, measureIdx, 2) }
                        onBlockAnchor(null)
                    }
                    else -> EditChip(icon = Icons.Filled.Repeat, text = null, enabled = true) {
                        onBlockAnchor(measureIdx)
                    }
                }
            }
        }
        // Linha da nota: casas / toggle de bateria + figuras + limpar.
        if (selection != null) {
            NoteEditor(
                revision = revision,
                tab = tab,
                track = track,
                selectedTrack = selectedTrack,
                selection = selection,
                accent = accent,
                onMutate = onMutate,
            )
        } else {
            Text(
                text = stringResource(R.string.tablature_editor_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textTertiary,
            )
        }
    }
}

@Composable
private fun AddTrackChip(onMutate: ((Tablature) -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        EditChip(
            icon = Icons.Filled.Add,
            text = stringResource(R.string.tablature_tracks_add),
            enabled = true,
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tablature_tracks_type_guitar)) },
                onClick = {
                    onMutate { it.addTrack("guitar") }
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tablature_tracks_type_bass)) },
                onClick = {
                    onMutate { it.addTrack("bass") }
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tablature_tracks_type_drums)) },
                onClick = {
                    onMutate { it.addTrack("drums") }
                    open = false
                },
            )
        }
    }
}

@Composable
private fun NoteEditor(
    revision: Int,
    tab: Tablature,
    track: Tablature.Track,
    selectedTrack: Int,
    selection: TabCellSelection,
    accent: Color,
    onMutate: ((Tablature) -> Unit) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    val located = tab.locate(selectedTrack, selection.col)
    val cell = located?.let { (measureIdx, stepIdx) ->
        track.measures.getOrNull(measureIdx)?.strings?.getOrNull(selection.row)?.steps?.getOrNull(stepIdx)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        when (track.type) {
            "drums" -> EditChip(
                icon = if (cell == null) Icons.Filled.RadioButtonUnchecked else Icons.Filled.Circle,
                text = null,
                enabled = true,
            ) {
                onMutate {
                    if (cell == null) {
                        it.setFret(selectedTrack, selection.col, selection.row, 1)
                    } else {
                        it.clearFret(selectedTrack, selection.col, selection.row)
                    }
                }
            }
            "keys" -> {
                // Linhas SATB guardam MIDI absoluto — degraus de semitom e
                // oitava com o nome da nota no meio.
                val baseMidi = track.rowsMeta.getOrNull(selection.row)?.baseMidi ?: 60
                val midi = cell?.v ?: baseMidi
                for ((delta, label) in listOf(-12 to "«", -1 to "‹")) {
                    EditChip(icon = null, text = label, enabled = midi + delta >= 21) {
                        onMutate { it.setFret(selectedTrack, selection.col, selection.row, midi + delta) }
                    }
                }
                Text(
                    text = noteName(midi),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (cell == null) CzTokens.textTertiary else accent,
                )
                for ((delta, label) in listOf(1 to "›", 12 to "»")) {
                    EditChip(icon = null, text = label, enabled = midi + delta <= 108) {
                        onMutate { it.setFret(selectedTrack, selection.col, selection.row, midi + delta) }
                    }
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (fret in 0..24) {
                    val active = cell?.v == fret
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .background(if (active) accent else CzTokens.surface, CircleShape)
                            .clickable {
                                onMutate { it.setFret(selectedTrack, selection.col, selection.row, fret) }
                            },
                    ) {
                        Text(
                            text = "$fret",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) Color.Black else CzTokens.textPrimary,
                        )
                    }
                }
            }
        }
        // Figuras: ♬=1 ♪=2 ♩=4 ½=8 1=16.
        for ((dur, label) in listOf(1 to "♬", 2 to "♪", 4 to "♩", 8 to "½", 16 to "1")) {
            val active = cell?.dur == dur
            val enabled = cell != null && track.type != "drums"
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 26.dp, height = 30.dp)
                    .background(
                        if (active) accent.copy(alpha = 0.18f) else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable(enabled = enabled) {
                        onMutate { it.setDuration(selectedTrack, selection.col, selection.row, dur, cell?.tup) }
                    },
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) accent else CzTokens.textTertiary,
                )
            }
        }
        if (track.type == "guitar" || track.type == "bass") {
            val pmActive = cell?.articulations?.get("pm") == true
            Text(
                text = stringResource(R.string.tablature_articulations_palm_mute_short),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = if (pmActive) Color.Black else CzTokens.textTertiary,
                modifier = Modifier
                    .background(if (pmActive) CzTokens.gold else CzTokens.surface, RoundedCornerShape(50))
                    .clickable(enabled = cell != null) {
                        onMutate { it.toggleArticulation(selectedTrack, selection.col, selection.row, "pm") }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.tablature_clear_cell),
            tint = if (cell == null) CzTokens.textTertiary else CzTokens.danger,
            modifier = Modifier
                .size(22.dp)
                .clickable(enabled = cell != null) {
                    onMutate { it.clearFret(selectedTrack, selection.col, selection.row) }
                }
                .testTag("tab.clearCell"),
        )
    }
}

private fun noteName(midi: Int): String {
    val name = MusicNotes.noteNames[((midi % 12) + 12) % 12]
    return "$name${midi / 12 - 1}"
}

@Composable
private fun EditChip(
    icon: ImageVector?,
    text: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(CzTokens.surface, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        val tint = if (enabled) CzTokens.textSecondary else CzTokens.textTertiary.copy(alpha = 0.5f)
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
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

// ---- transporte ----

@Composable
private fun Transport(
    accent: Color,
    isPlaying: Boolean,
    countInEnabled: Boolean,
    loopEnabled: Boolean,
    bpm: Int,
    onToggle: () -> Unit,
    onCountIn: () -> Unit,
    onLoop: () -> Unit,
    onBpm: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .background(accent, RoundedCornerShape(50))
                .clickable(onClick = onToggle)
                .padding(horizontal = 22.dp, vertical = 16.dp) // ~50 pt como o .glassProminent do iOS
                .testTag("tab.toggle"),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(if (isPlaying) R.string.tablature_stop else R.string.tablature_play),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = Color.Black,
            )
        }
        RoundToggle(
            icon = painterResource(R.drawable.ic_tab_metronome), // o metrônomo do iOS, não um cronômetro
            active = countInEnabled,
            accent = accent,
            tag = "tab.countin",
            onClick = onCountIn,
        )
        RoundToggle(
            icon = rememberVectorPainter(Icons.Filled.Repeat),
            active = loopEnabled,
            accent = accent,
            tag = "tab.loop",
            onClick = onLoop,
        )
        Spacer(Modifier.weight(1f))
        RoundButton(icon = Icons.Filled.Remove) { onBpm(-5) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$bpm",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = CzTokens.textPrimary,
            )
            Text(
                text = stringResource(R.string.tablature_bpm),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textTertiary,
            )
        }
        RoundButton(icon = Icons.Filled.Add) { onBpm(+5) }
    }
}

@Composable
private fun RoundToggle(
    icon: androidx.compose.ui.graphics.painter.Painter,
    active: Boolean,
    accent: Color,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .background(CzTokens.surface, CircleShape)
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = if (active) accent else CzTokens.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(34.dp)
            .background(CzTokens.surface, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CzTokens.textPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}
