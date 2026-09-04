package com.levelhard.cadentia.features.stems

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.LocalQaFlags
import com.levelhard.cadentia.R
import com.levelhard.cadentia.features.library.RoqueOSSection
import com.levelhard.cadentia.kit.RecentSong
import com.levelhard.cadentia.kit.RecentSongs
import com.levelhard.cadentia.kit.SetQueue
import com.levelhard.cadentia.kit.Setlist
import com.levelhard.cadentia.kit.Setlists
import com.levelhard.cadentia.kit.SongSearch
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Separar — port do `StemsView.swift` (1.16) sobre o [StemsModel]: biblioteca
 * (recentes com busca e "Ver todas", repertórios com fila em ordem/aleatório,
 * espaço usado com botão de liberar), importação de várias músicas de uma vez
 * (a leva em série, com a faixa de andamento no topo e cancelar), a tela de
 * separação com a fila, o player das quatro faixas com onda viva, loop A/B em
 * um botão, mixer em folha e memória de ajuste por música.
 *
 * A SEPARAÇÃO em si não existe nesta build: o modelo (103 MB no iOS, ausente
 * até do clone) não tem equivalente ONNX gerado ainda. Abrir uma música nova
 * normaliza o arquivo, sobe o serviço de primeiro plano e para no fato, com
 * todas as letras (`cadentia.stems.modelMissing`) — o MESMO estado do iOS sem o
 * `Separator.mlmodelc`. Quem já tem as quatro faixas em disco toca normal.
 */
@Composable
fun StemsScreen() {
    val accent = CzTokens.stemsTeal
    val context = LocalContext.current
    val qa = LocalQaFlags.current
    val model = remember { StemsModel(context) }
    val engine = model.engine

    var showMixer by remember { mutableStateOf(false) }
    var playhead by remember { mutableDoubleStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }

    // Android 13+: a notificação da leva só aparece com permissão, e ela é
    // pedida INLINE, no momento em que a pessoa manda separar — nunca na
    // abertura do app. Negada, a leva roda igual: só a tela informa.
    var pendingPicks by remember { mutableStateOf<List<StemsModel.Pick>?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingPicks?.let { model.openMany(it) }
        pendingPicks = null
    }
    fun separate(picks: List<StemsModel.Pick>) {
        if (picks.isEmpty()) return
        val needsAsk = Build.VERSION.SDK_INT >= 33 && !SeparationService.canNotify(context) &&
            picks.any { !model.isReady(it.song) }
        if (needsAsk) {
            pendingPicks = picks
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            model.openMany(picks)
        }
    }

    // `OpenMultipleDocuments` é a diferença entre separar uma música e separar
    // um repertório: o pedido do founder foi exatamente esse.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picks = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = displayName(context, uri) ?: return@mapNotNull null
            StemsModel.Pick(
                uri = uri,
                song = RecentSong(
                    title = name.substringBeforeLast('.'),
                    source = RecentSong.Source.Device(persistedUri = uri.toString(), filename = name),
                    lastOpenedEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        separate(picks)
    }

    LaunchedEffect(Unit) {
        // `-qa-stems-many`: uma playlist inteira nas Recentes, sem abrir nada.
        if (qa.stemsMany) {
            StemsModel.QA_TITLES.forEachIndexed { index, title ->
                model.rememberForQA(
                    RecentSong(
                        title = title,
                        source = RecentSong.Source.Device(persistedUri = "qa://recentes/$index", filename = "$title.mp3"),
                        lastOpenedEpochMillis = System.currentTimeMillis() - index * 60_000L,
                    ),
                )
            }
        }
        // Entrar na tela é o momento em que nenhuma separação está em curso:
        // toda pasta pela metade é entulho, e é o único gatilho de revisão de
        // espaço que não depende de separar uma música nova.
        model.sweepStorage()
        // `-qa-stems-progress 42`: a tela de separação parada em 42%.
        qa.stemsProgress?.let { percent ->
            model.showSeparatingForQA("Bohemian Rhapsody", percent, 100, 9.0, qa.stemsBatch)
        }
        qa.stemsBanner?.let { failed -> model.showBatchBannerForQA(total = 5, failed = failed) }
        // `-qa-stems-file a.wav;b.mp3`: separação de verdade, sem o seletor.
        qa.stemsFile?.let { paths ->
            val picks = paths.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { path ->
                val file = java.io.File(path)
                StemsModel.Pick(
                    uri = Uri.fromFile(file),
                    song = RecentSong(
                        title = file.nameWithoutExtension,
                        source = RecentSong.Source.Device(persistedUri = Uri.fromFile(file).toString(), filename = file.name),
                        lastOpenedEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
            if (picks.isNotEmpty()) separate(picks)
        }
    }
    // Ticker: playhead, loop A/B e fim natural.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(50)
            engine.tickLoop()
            playhead = engine.currentTime
            if (isPlaying != engine.isPlaying) isPlaying = engine.isPlaying
        }
    }
    DisposableEffect(Unit) {
        onDispose { model.shutdown() }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        @Suppress("UNUSED_EXPRESSION") model.revision
        val phase = model.phase
        Column(Modifier.fillMaxSize()) {
            // A leva aparece em qualquer estado: a primeira música já está
            // tocando enquanto o resto da playlist ainda separa. Na tela de
            // separação ela vira eco (a fila logo abaixo diz o mesmo com mais
            // detalhe), então ali fica de fora.
            val batch = model.batch
            if (batch != null && !phase.isWorking) {
                BatchBanner(batch = batch, accent = accent, onDismiss = { model.dismissBatch() })
            }
            Box(Modifier.weight(1f)) {
                when {
                    // A separação NÃO rola: ocupa a tela, centrada.
                    phase.isWorking -> SeparatingView(
                        progress = (phase as? StemsModel.Phase.Separating)
                            ?.let { if (it.total > 0) it.done.toDouble() / it.total else null },
                        title = model.songTitle,
                        accent = accent,
                        startedAtMillis = model.workStartedAt,
                        batch = batch,
                        modelDownload = model.modelDownload,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = 560.dp)
                            .padding(bottom = 60.dp),
                    )
                    phase is StemsModel.Phase.Ready -> PlayerState(
                        engine = engine,
                        accent = accent,
                        songTitle = model.songTitle,
                        queue = model.queue,
                        playhead = playhead,
                        isPlaying = isPlaying,
                        loopAnchor = model.loopAnchor,
                        revision = model.revision,
                        onBack = { model.reset() },
                        onPlayPause = {
                            if (engine.isPlaying) {
                                engine.pause()
                                model.persistMix()
                            } else {
                                engine.play()
                            }
                            isPlaying = engine.isPlaying
                        },
                        onSeek = { engine.seek(it) },
                        onSkip = { engine.seek(engine.currentTime + it) },
                        onLoop = { model.cycleLoop() },
                        onMixer = { showMixer = true },
                        onQueuePrev = { model.goBackInQueue() },
                        onQueueNext = { model.advanceQueue() },
                    )
                    else -> LibraryState(
                        accent = accent,
                        phase = phase,
                        recent = model.recent,
                        setlists = model.setlists,
                        isReady = { model.isReady(it) },
                        revision = model.revision,
                        storageUsage = { model.cache.usage() },
                        roqueSection = {
                            RoqueOSSection(
                                accent = accent,
                                account = model.account,
                                onPick = { model.openRemoteItem(it) },
                                onPickMany = { downloaded ->
                                    separate(downloaded.map { (uri, song) -> StemsModel.Pick(uri, song, temporary = true) })
                                },
                                downloadingId = model.downloadingId,
                            )
                        },
                        onOpenFile = { importer.launch(arrayOf("audio/*")) },
                        onReopen = { model.reopen(it) },
                        onForget = { model.forget(it) },
                        onClearStorage = { model.clearStorage() },
                        onTryAgain = { model.reset() },
                        onCreateSetlist = { model.createSetlist(it) },
                        onAddToSetlist = { song, listId -> model.addToSetlist(song, listId) },
                        onRemoveFromSetlist = { songId, listId -> model.removeFromSetlist(songId, listId) },
                        onDuplicateSetlist = { listId, name -> model.duplicateSetlist(listId, name) },
                        onRenameSetlist = { listId, name -> model.renameSetlist(listId, name) },
                        onDeleteSetlist = { model.deleteSetlist(it) },
                        onPlaySetlist = { list, mode -> model.playSetlist(list, mode) },
                        onPlayFromSetlist = { list, song -> model.playFromSetlist(list, song) },
                    )
                }
            }
        }
    }

    if (showMixer) {
        StemMixerSheet(
            engine = engine,
            accent = accent,
            revision = model.revision,
            onDismiss = {
                showMixer = false
                model.persistMix()
            },
            onChanged = { model.persistMix() },
        )
    }
}

/** O nome que o provedor dá ao arquivo (o que a pessoa vê no seletor), ou o fim da URI. */
private fun displayName(context: android.content.Context, uri: Uri): String? {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
}

// ---- a leva ----

/**
 * O andamento de uma leva de músicas, em uma faixa no topo. Diz três coisas,
 * e as três importam: **quantas faltam**, **qual está separando agora**, e
 * **quais não deram**. O X é SEMPRE visível e cancela de verdade: uma leva de
 * vinte escolhida por engano eram quatro minutos de GPU sem controle na tela.
 */
@Composable
private fun BatchBanner(batch: StemsModel.ImportBatch, accent: Color, onDismiss: () -> Unit) {
    val songs = if (batch.total > 0) batch.done.toDouble() / batch.total else 0.0
    val windows = if (batch.windowsTotal > 0) batch.windowsDone.toDouble() / batch.windowsTotal else 0.0
    // O andamento real: as músicas prontas mais o pedaço da que está em curso.
    val progress = if (batch.total > 0) (batch.done + windows) / batch.total else songs
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp)
                .shadow(12.dp, RoundedCornerShape(14.dp))
                // OPACO: `surface` é branco a 5%, e o que estivesse atrás lia
                // por cima do texto. Fundo de palco primeiro, brilho por cima.
                .background(CzTokens.stageBottom.copy(alpha = 0.97f), RoundedCornerShape(14.dp))
                .background(CzTokens.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp)
                .testTag("stems.batch"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (batch.isFinished) {
                    Icon(
                        imageVector = if (batch.failed.isEmpty()) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (batch.failed.isEmpty()) accent else CzTokens.warnAmber,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = stringResource(
                        R.string.cadentia_stems_batch_progress,
                        minOf(batch.done + (if (batch.isFinished) 0 else 1), batch.total), batch.total,
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CzTokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                val dismissLabel = stringResource(
                    if (batch.isFinished) R.string.cadentia_stems_batch_dismiss else R.string.cadentia_stems_batch_cancel,
                )
                // 44×44 e não o tamanho do glifo: é o único jeito de matar a leva.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = dismissLabel }
                        .testTag("stems.batch.dismiss"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = CzTokens.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (!batch.isFinished) {
                Text(
                    text = batch.title,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = CzTokens.textTertiary,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0.0, 1.0).toFloat() },
                    color = accent,
                    trackColor = CzTokens.surface,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (batch.failed.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.cadentia_stems_batch_failed, batch.failed.joinToString(", ")),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = CzTokens.warnAmber,
                    modifier = Modifier.testTag("stems.batch.failed"),
                )
            }
        }
    }
}

// ---- player ----

@Composable
private fun PlayerState(
    engine: StemPlayerEngine,
    accent: Color,
    songTitle: String,
    queue: SetQueue?,
    playhead: Double,
    isPlaying: Boolean,
    loopAnchor: Double?,
    revision: Int,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onSkip: (Double) -> Unit,
    onLoop: () -> Unit,
    onMixer: () -> Unit,
    onQueuePrev: () -> Unit,
    onQueueNext: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp)
            .widthIn(max = 560.dp),
    ) {
        // Voltar tradicional + título.
        Box(Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .background(CzTokens.surface, CircleShape)
                    .clickable(onClick = onBack)
                    .testTag("stems.back"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cadentia_stems_back_to_library),
                    tint = CzTokens.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = songTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = CzTokens.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 46.dp)
                    .testTag("stems.title"),
            )
        }

        Spacer(Modifier.height(10.dp))

        // Cromo da fila do repertório, só tocando de um set.
        if (queue != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .background(CzTokens.surface, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.cadentia_setlists_previous_song),
                    tint = CzTokens.textSecondary,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(22.dp)
                        .clickable(onClick = onQueuePrev)
                        .testTag("stems.queuePrev"),
                )
                Icon(
                    imageVector = if (queue.mode == SetQueue.Mode.Shuffled) Icons.Filled.Shuffle else Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = queue.listName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = CzTokens.textSecondary,
                )
                Text(
                    text = "${queue.position.first}/${queue.position.second}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textTertiary,
                    modifier = Modifier.testTag("stems.queuePosition"),
                )
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.cadentia_setlists_next_song),
                    tint = CzTokens.textSecondary,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(22.dp)
                        .clickable(onClick = onQueueNext)
                        .testTag("stems.queueNext"),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Pontos das faixas: aceso = soando, e é dela a cor da onda.
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val anySolo = engine.tracks.any { it.isSoloed }
            for (track in engine.tracks) {
                val audible = if (anySolo) track.isSoloed else !track.isMuted
                Box(
                    Modifier
                        .size(8.dp)
                        .alpha(if (audible) 1f else 0.16f)
                        .background(stemColors[track.id] ?: accent, CircleShape),
                )
            }
        }

        // A onda absorve a folga da tela.
        StemWaveform(
            spectrum = engine.spectrum,
            levels = engine.levels,
            tint = accent,
            isPlaying = isPlaying,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 190.dp)
                .padding(vertical = 12.dp),
        )

        // Transporte.
        ThinSlider(
            value = if (engine.duration > 0) playhead / engine.duration else 0.0,
            accent = accent,
            loopRange = engine.practiceLoop?.let {
                if (engine.duration > 0) (it.start / engine.duration)..(it.end / engine.duration) else null
            },
            onChange = { onSeek(it * engine.duration) },
            modifier = Modifier.testTag("stems.scrubber"),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = clock(playhead),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textTertiary,
                modifier = Modifier.testTag("stems.elapsed"),
            )
            Spacer(Modifier.weight(1f))
            // Restante, não total: o número de quem estuda.
            Text(
                text = "-" + clock(maxOf(engine.duration - playhead, 0.0)),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textTertiary,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = null, // PENDÊNCIA a11y: sem chave "voltar 10 s" no catálogo
                tint = CzTokens.textSecondary,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .clickable { onSkip(-10.0) }
                    .padding(6.dp)
                    .testTag("stems.skip-10"),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(68.dp)
                    .background(accent, CircleShape)
                    .clickable(onClick = onPlayPause)
                    .testTag("stems.play"),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.music_metronome_stop else R.string.music_metronome_start,
                    ),
                    tint = CzTokens.stageBottom,
                    modifier = Modifier.size(30.dp),
                )
            }
            Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = null, // PENDÊNCIA a11y: sem chave "avançar 10 s" no catálogo
                tint = CzTokens.textSecondary,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .clickable { onSkip(10.0) }
                    .padding(6.dp)
                    .testTag("stems.skip10"),
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            // O loop de estudo em UM botão de três estados.
            val hasLoop = engine.practiceLoop != null
            val armed = loopAnchor != null
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 56.dp, height = 50.dp)
                    .background(
                        if (hasLoop) accent else CzTokens.surface,
                        RoundedCornerShape(CzTokens.radiusMD),
                    )
                    .border(
                        1.5.dp,
                        if (armed && !hasLoop) accent else Color.Transparent,
                        RoundedCornerShape(CzTokens.radiusMD),
                    )
                    .clickable(onClick = onLoop)
                    .testTag("stems.loop"),
            ) {
                when {
                    hasLoop -> Text(
                        text = "A-B", // i18n-verbatim: notação de loop, igual nos 10
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = CzTokens.stageBottom,
                    )
                    armed -> Text(
                        text = "A", // i18n-verbatim: ponto A do loop
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = accent,
                    )
                    else -> Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = stringResource(R.string.cadentia_stems_loop),
                        tint = CzTokens.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(CzTokens.radiusMD))
                    .clickable(onClick = onMixer)
                    .testTag("stems.mixer"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = stringResource(R.string.cadentia_stems_mixer),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
        }
    }
}

private fun clock(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "0:00"
    val total = seconds.toInt()
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

// ---- biblioteca e estados de espera ----

@Composable
private fun LibraryState(
    accent: Color,
    phase: StemsModel.Phase,
    recent: RecentSongs,
    setlists: Setlists,
    isReady: (RecentSong) -> Boolean,
    revision: Int,
    /** Mede o disco; chamado FORA da thread principal. */
    storageUsage: () -> StemCache.Usage,
    roqueSection: @Composable () -> Unit,
    onOpenFile: () -> Unit,
    onReopen: (RecentSong) -> Unit,
    onForget: (RecentSong) -> Unit,
    onClearStorage: () -> Unit,
    onTryAgain: () -> Unit,
    onCreateSetlist: (String) -> Unit,
    onAddToSetlist: (RecentSong, String) -> Unit,
    onRemoveFromSetlist: (String, String) -> Unit,
    onDuplicateSetlist: (String, String) -> Unit,
    onRenameSetlist: (String, String) -> Unit,
    onDeleteSetlist: (String) -> Unit,
    onPlaySetlist: (Setlist, SetQueue.Mode) -> Unit,
    onPlayFromSetlist: (Setlist, RecentSong) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var newListName by remember { mutableStateOf("") }
    var showingAllRecent by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    // Quanto disco as faixas separadas ocupam. Medido FORA da thread principal:
    // são centenas de arquivos com uma playlist separada, e trabalho de disco
    // no caminho da interface é o defeito que travava a tela da bateria.
    var storage by remember { mutableStateOf<StemCache.Usage?>(null) }
    LaunchedEffect(revision, recent.songs.size) {
        storage = withContext(Dispatchers.IO) { storageUsage() }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            containerColor = CzTokens.stageTop,
            titleContentColor = CzTokens.textPrimary,
            textContentColor = CzTokens.textSecondary,
            title = { Text(stringResource(R.string.cadentia_library_clear_storage_title)) },
            text = { Text(stringResource(R.string.cadentia_library_clear_storage_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onClearStorage()
                    },
                    modifier = Modifier.testTag("library.clearStorage"),
                ) {
                    Text(stringResource(R.string.cadentia_library_clear_storage), color = CzTokens.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.cadentia_setlists_cancel), color = CzTokens.textSecondary)
                }
            },
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            if (phase is StemsModel.Phase.Failed) {
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = CzTokens.warnAmber,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = stringResource(R.string.cadentia_stems_failed),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CzTokens.textPrimary,
                        )
                        // O motivo técnico curto enquanto a feature é nova.
                        if (phase.detail.isNotEmpty()) {
                            Text(
                                text = phase.detail,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                color = CzTokens.textTertiary,
                                modifier = Modifier.testTag("stems.failureReason"),
                            )
                        }
                        Text(
                            text = stringResource(R.string.cadentia_stems_try_again),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            modifier = Modifier.clickable(onClick = onTryAgain),
                        )
                    }
                }
            }

            // Sem nada ainda, o convite (os cartões de origem, logo abaixo, são o botão).
            if (recent.songs.isEmpty() && setlists.lists.isEmpty() && phase !is StemsModel.Phase.Failed) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.8f),
                        modifier = Modifier.size(38.dp),
                    )
                    Text(
                        text = stringResource(R.string.cadentia_stems_empty_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = CzTokens.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.cadentia_stems_empty_body),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = CzTokens.textSecondary,
                    )
                }
            }

            // ---- repertórios ----
            // Acima das origens, como no iOS: quem montou o set de sábado abre o
            // app atrás DELE. A seção só aparece com conteúdo ou com recentes para
            // adicionar (o criar precisa de onde nascer).
            if (setlists.lists.isNotEmpty() || recent.songs.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.cadentia_setlists_section),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CzTokens.textTertiary,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        label = { Text(stringResource(R.string.cadentia_setlists_name_placeholder)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CzTokens.textPrimary,
                            unfocusedTextColor = CzTokens.textPrimary,
                            focusedBorderColor = accent,
                            unfocusedBorderColor = CzTokens.hairline,
                            focusedLabelColor = accent, // rótulo em dourado (primary do tema) sobre borda teal (QA)
                            unfocusedLabelColor = CzTokens.textTertiary,
                            cursorColor = accent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.cadentia_setlists_new),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (newListName.isNotBlank()) accent else CzTokens.textTertiary,
                        modifier = Modifier
                            .clickable(enabled = newListName.isNotBlank()) {
                                onCreateSetlist(newListName)
                                newListName = ""
                            }
                            .padding(8.dp),
                    )
                }
                // Sem repertório nenhum o iOS mostra só o cabeçalho e o criar:
                // cadentia.setlists.empty ("use Adicionar músicas aqui em cima") é
                // do detalhe de UM repertório sem músicas — aqui apontava para um
                // botão que não existe (achado do QA no emulador).
                for (list in setlists.lists) {
                    SetlistCard(
                        revision = revision,
                        list = list,
                        accent = accent,
                        candidates = recent.songs.filter { candidate -> list.songs.none { it.id == candidate.id } },
                        hasRecents = recent.songs.isNotEmpty(),
                        onAddSong = { onAddToSetlist(it, list.id) },
                        onPlayOrdered = { onPlaySetlist(list, SetQueue.Mode.Ordered) },
                        onPlayShuffled = { onPlaySetlist(list, SetQueue.Mode.Shuffled) },
                        onPlayFrom = { onPlayFromSetlist(list, it) },
                        onRemoveSong = { onRemoveFromSetlist(it, list.id) },
                        onDuplicate = { onDuplicateSetlist(list.id, it) },
                        onRename = { onRenameSetlist(list.id, it) },
                        onDelete = { onDeleteSetlist(list.id) },
                    )
                }
            }

            // ---- recentes ----
            if (recent.songs.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cadentia_library_recent),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CzTokens.textTertiary,
                    )
                    Spacer(Modifier.weight(1f))
                    // Tocável, e não só um rótulo: agora que as faixas FICAM, o
                    // app pode crescer para gigabytes, e um número que só
                    // informa deixa a pessoa sem saída a não ser apagar o app.
                    val used = storage
                    if (used != null && used.songs > 0) {
                        val clearHint = stringResource(R.string.cadentia_library_clear_storage_title)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clickable { confirmingClear = true }
                                .padding(vertical = 8.dp, horizontal = 6.dp)
                                .semantics { contentDescription = clearHint }
                                .testTag("library.storageUsed"),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.cadentia_library_storage_used,
                                    used.songs, Formatter.formatFileSize(context, used.bytes),
                                ),
                                fontSize = 11.sp,
                                maxLines = 1,
                                color = CzTokens.textTertiary,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = CzTokens.textTertiary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.cadentia_setlists_search_songs)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CzTokens.textPrimary,
                        unfocusedTextColor = CzTokens.textPrimary,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = CzTokens.hairline,
                        focusedLabelColor = accent, // rótulo em dourado (primary do tema) sobre borda teal (QA)
                        unfocusedLabelColor = CzTokens.textTertiary,
                        cursorColor = accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = SongSearch.filter(recent.songs, search)
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.cadentia_setlists_no_matches),
                        fontSize = 12.sp,
                        color = CzTokens.textTertiary,
                    )
                }
                // Seis cabem antes de a lista virar rolagem sem fim; o resto
                // fica atrás de UM toque, não atrás de um limite (a busca
                // mostra tudo que casar). Separar uma playlist e só alcançar as
                // seis primeiras era metade do problema que o founder relatou.
                val collapsed = search.isBlank() && !showingAllRecent && filtered.size > COLLAPSED_RECENT
                val shown = if (collapsed) filtered.take(COLLAPSED_RECENT) else filtered
                for (song in shown) {
                    SongRow(
                        revision = revision,
                        song = song,
                        ready = isReady(song),
                        accent = accent,
                        setlists = setlists,
                        onOpen = { onReopen(song) },
                        onForget = { onForget(song) },
                        onAddTo = { listId -> onAddToSetlist(song, listId) },
                    )
                }
                if (search.isBlank() && filtered.size > COLLAPSED_RECENT) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showingAllRecent = !showingAllRecent }
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                            .testTag("library.recent.showAll"),
                    ) {
                        Icon(
                            imageVector = if (showingAllRecent) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (showingAllRecent) {
                                stringResource(R.string.cadentia_library_show_fewer)
                            } else {
                                stringResource(R.string.cadentia_library_show_all, filtered.size)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = accent,
                        )
                    }
                }
            }

            // ---- neste aparelho ----
            // O seletor do sistema, com seleção múltipla: separar uma música ou
            // um repertório inteiro é o mesmo toque.
            CzCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenFile)
                        .padding(14.dp)
                        .testTag("stems.choose"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.cadentia_library_this_device),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CzTokens.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.cadentia_library_this_device_hint),
                            fontSize = 11.sp,
                            color = CzTokens.textTertiary,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = CzTokens.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // ---- RoqueOS ----
            roqueSection()
        }
    }
}

/** Quantas recentes cabem antes de a lista virar rolagem sem fim. */
private const val COLLAPSED_RECENT = 6

@Composable
private fun SongRow(
    revision: Int,
    song: RecentSong,
    ready: Boolean,
    accent: Color,
    setlists: Setlists,
    onOpen: () -> Unit,
    onForget: () -> Unit,
    onAddTo: (String) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    var showAdd by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(CzTokens.surface, RoundedCornerShape(CzTokens.radiusMD))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Icon(
            imageVector = if (ready) Icons.Filled.PlayCircle else Icons.Filled.MusicNote,
            contentDescription = null,
            tint = if (ready) accent else CzTokens.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = song.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = CzTokens.textPrimary,
            )
            // Dizer que está pronta vale muito: é a diferença entre tocar na
            // hora e esperar a separação de novo.
            Text(
                text = stringResource(
                    if (ready) R.string.cadentia_library_ready_to_play else R.string.cadentia_library_will_separate_again,
                ),
                fontSize = 10.sp,
                color = if (ready) accent.copy(alpha = 0.85f) else CzTokens.textTertiary,
            )
        }
        if (setlists.lists.isNotEmpty()) {
            Box {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.cadentia_setlists_add_to),
                    tint = CzTokens.textTertiary,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { showAdd = true }
                        .padding(4.dp),
                )
                DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                    for (list in setlists.lists) {
                        DropdownMenuItem(
                            text = { Text(list.name) },
                            onClick = {
                                onAddTo(list.id)
                                showAdd = false
                            },
                        )
                    }
                }
            }
        }
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.cadentia_library_remove_from_recent),
            tint = CzTokens.textTertiary,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(24.dp)
                .clickable(onClick = onForget)
                .padding(4.dp),
        )
    }
}

@Composable
private fun SetlistCard(
    revision: Int,
    list: Setlist,
    accent: Color,
    candidates: List<RecentSong>,
    hasRecents: Boolean,
    onAddSong: (RecentSong) -> Unit,
    onPlayOrdered: () -> Unit,
    onPlayShuffled: () -> Unit,
    onPlayFrom: (RecentSong) -> Unit,
    onRemoveSong: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(list.name) }
    var adding by remember { mutableStateOf(false) }

    if (adding) {
        AddSongsSheet(
            accent = accent,
            candidates = candidates,
            hasRecents = hasRecents,
            onAdd = onAddSong,
            onDismiss = { adding = false },
        )
    }

    CzCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = list.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    color = CzTokens.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded },
                )
                Text(
                    text = "${list.songs.size}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textTertiary,
                )
                if (list.songs.isNotEmpty()) {
                    SmallAction(Icons.Filled.PlayArrow, stringResource(R.string.cadentia_setlists_play_ordered), accent, onPlayOrdered)
                    SmallAction(Icons.Filled.Shuffle, stringResource(R.string.cadentia_setlists_play_shuffled), accent, onPlayShuffled)
                }
                SmallAction(Icons.Filled.ContentCopy, stringResource(R.string.cadentia_setlists_duplicate), CzTokens.textSecondary) {
                    onDuplicate(list.name + " 2") // i18n-verbatim: sufixo numérico
                }
                SmallAction(Icons.Filled.Edit, stringResource(R.string.cadentia_setlists_rename), CzTokens.textSecondary) {
                    editing = !editing
                    editName = list.name
                }
                SmallAction(Icons.Filled.Delete, stringResource(R.string.cadentia_setlists_delete), CzTokens.danger, onDelete)
            }
            if (editing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CzTokens.textPrimary,
                            unfocusedTextColor = CzTokens.textPrimary,
                            focusedBorderColor = accent,
                            unfocusedBorderColor = CzTokens.hairline,
                            focusedLabelColor = accent, // rótulo em dourado (primary do tema) sobre borda teal (QA)
                            unfocusedLabelColor = CzTokens.textTertiary,
                            cursorColor = accent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.cadentia_setlists_confirm),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier
                            .clickable {
                                onRename(editName)
                                editing = false
                            }
                            .padding(6.dp),
                    )
                }
            }
            if (expanded) {
                // "Adicionar músicas" de verdade (o botão do detalhe do iOS):
                // abre a folha com as Recentes que ainda não estão na lista.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CzTokens.surface, RoundedCornerShape(CzTokens.radiusMD))
                        .clickable { adding = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("setlist.addSongs"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddCircle,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.cadentia_setlists_add_songs),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CzTokens.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = CzTokens.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (list.songs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.cadentia_setlists_empty),
                        fontSize = 12.sp,
                        color = CzTokens.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
                for (song in list.songs) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayFrom(song) }
                            .padding(vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = CzTokens.textTertiary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = song.title,
                            fontSize = 13.sp,
                            maxLines = 1,
                            color = CzTokens.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.cadentia_setlists_remove_song),
                            tint = CzTokens.textTertiary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onRemoveSong(song.id) }
                                .padding(3.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Folha "Adicionar músicas" — port do picker do SetlistDetailSheet: as
 * Recentes que ainda não estão no repertório, com busca sem acento; sem
 * Recentes explica que é preciso abrir uma música primeiro.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddSongsSheet(
    accent: Color,
    candidates: List<RecentSong>,
    hasRecents: Boolean,
    onAdd: (RecentSong) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CzTokens.stageTop,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.cadentia_setlists_add_songs),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = CzTokens.textPrimary,
            )
            if (!hasRecents) {
                Text(
                    text = stringResource(R.string.cadentia_setlists_no_recents),
                    fontSize = 13.sp,
                    color = CzTokens.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            } else {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.cadentia_setlists_search_songs)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CzTokens.textPrimary,
                        unfocusedTextColor = CzTokens.textPrimary,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = CzTokens.hairline,
                        focusedLabelColor = accent, // rótulo em dourado (primary do tema) sobre borda teal (QA)
                        unfocusedLabelColor = CzTokens.textTertiary,
                        cursorColor = accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = SongSearch.filter(candidates, search)
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.cadentia_setlists_no_matches),
                        fontSize = 12.sp,
                        color = CzTokens.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
                for (song in filtered) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CzTokens.surface, RoundedCornerShape(CzTokens.radiusMD))
                            .clickable { onAdd(song) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = song.title,
                            fontSize = 14.sp,
                            maxLines = 1,
                            color = CzTokens.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Filled.AddCircle,
                            contentDescription = null,
                            tint = CzTokens.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(26.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
    )
}
