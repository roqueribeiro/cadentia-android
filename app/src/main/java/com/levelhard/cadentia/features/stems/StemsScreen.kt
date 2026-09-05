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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.BottomSheetScaffold
import com.levelhard.cadentia.ui.rememberReduceMotion
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContent
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.exposeTestTags
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
 * A separação roda no `OnnxStemBackend` com o modelo em `filesDir/models`.
 * Sem o modelo, abrir uma música nova normaliza o arquivo, sobe o serviço de
 * primeiro plano e para no fato, com todas as letras
 * (`cadentia.stems.modelMissing`) — o MESMO estado do iOS sem o
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
        // `-qa-stems-demo`: quatro faixas prontas no cache e o player aberto
        // direto — player, onda, medidores, mixer e velocidade sem separar.
        // `-qa-stems-mixer` abre a folha do mixer por cima: o player sozinho
        // mostra uma onda, e onda qualquer tocador tem; os quatro faders com
        // nome é que provam a separação (captura de loja).
        if (qa.stemsDemo) {
            val seeded = withContext(Dispatchers.IO) { StemsQA.seed(model.cache, second = qa.stemsDemo2) }
            if (seeded) {
                if (qa.stemsDemo2) model.rememberForQA(StemsQA.secondSong)
                model.reopen(StemsQA.song)
                if (qa.stemsMixer) {
                    // A folha só faz sentido sobre o player: espera as faixas abrirem.
                    while (model.phase !is StemsModel.Phase.Ready) delay(50)
                    showMixer = true
                }
            }
            return@LaunchedEffect
        }
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

    // O voltar do sistema desce um nível, como o gesto no iOS: fecha o mixer
    // se ele estiver aberto, senão sai do player para a biblioteca. Sem isto
    // o voltar com o mixer aberto largava a pessoa na tela inicial do
    // aparelho (achado do teste instrumentado).
    val showingPlayer = model.phase is StemsModel.Phase.Ready
    androidx.activity.compose.BackHandler(enabled = showMixer || showingPlayer) {
        if (showMixer) {
            showMixer = false
            model.persistMix()
        } else {
            model.reset()
        }
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
                        // Numa leva, o nome de QUEM está separando agora. O iOS
                        // 1.16 mostra `songTitle` (a primeira música, que já está
                        // no player) durante a leva inteira — defeito dele.
                        title = batch?.title?.takeIf { it.isNotEmpty() } ?: model.songTitle,
                        accent = accent,
                        startedAtMillis = model.workStartedAt,
                        batch = batch,
                        modelDownload = model.modelDownload,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = 560.dp)
                            .padding(bottom = 60.dp),
                    )
                    // O mixer numa folha COM DETENTES por cima do player, sem
                    // véu: a música segue, a onda continua visível e o
                    // transporte responde com a folha aberta — o
                    // `presentationDetents` + `presentationBackgroundInteraction`
                    // do iOS. Recolhida (300 dp), no meio (62 %) ou inteira.
                    phase is StemsModel.Phase.Ready -> MixerScaffold(
                        showMixer = showMixer,
                        onMixerHidden = {
                            showMixer = false
                            model.persistMix()
                        },
                        sheet = {
                            StemMixerContent(
                                engine = engine, accent = accent, revision = model.revision,
                                onChanged = { model.persistMix() },
                            )
                        },
                    ) { PlayerState(
                        engine = engine,
                        accent = accent,
                        songKey = model.currentSongId ?: model.songTitle,
                        forward = model.lastStep == StemsModel.QueueStep.Forward,
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
                    ) }
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
                        onCreateSetlistWith = { name, song -> model.createSetlistWith(name, song) },
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

}

/**
 * Folha padrão (não modal) com três detentes sobre o player. O Material 3
 * dá dois estados (recolhida/expandida); o do meio do iOS (62 %) vira a
 * altura recolhida generosa, e "inteira" é a expandida. Escondida quando a
 * pessoa arrasta para baixo, e o estado da tela acompanha.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixerScaffold(
    showMixer: Boolean,
    onMixerHidden: () -> Unit,
    sheet: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        skipHiddenState = false,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    LaunchedEffect(showMixer) {
        if (showMixer) sheetState.partialExpand() else if (sheetState.currentValue != SheetValue.Hidden) sheetState.hide()
    }
    // Arrastou para baixo até sumir: a tela fica sabendo.
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue == SheetValue.Hidden && showMixer) onMixerHidden()
    }
    // A folha escondida fica logo abaixo da borda do scaffold, e a barra de
    // abas é translúcida: sem o clip o topo do mixer ("Tracks", "Drums")
    // aparece por trás dela (visto no QA f15k). O clip tem que envolver o
    // scaffold INTEIRO: o `modifier` do BottomSheetScaffold (M3 1.3) vai só
    // para a Surface do corpo, e um clipToBounds ali não alcança a folha.
    Box(Modifier.fillMaxSize().clipToBounds()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 300.dp,
            sheetContainerColor = CzTokens.stageTop,
            sheetContentColor = CzTokens.textPrimary,
            sheetShadowElevation = 12.dp,
            containerColor = Color.Transparent,
            sheetContent = { sheet() },
        ) { _ ->
            // Sem o padding do scaffold: a folha passa POR CIMA do player, como
            // no iOS, em vez de encolher a onda em 300 dp com a folha escondida.
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/**
 * Avançar entra pela direita e sai pela esquerda; voltar, o contrário. Com
 * Reduzir Movimento, só o fade. 400 ms como o `.snappy(duration: 0.4)` do iOS.
 */
private fun <T> songTransition(forward: Boolean, reduceMotion: Boolean): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    if (reduceMotion) {
        fadeIn(tween(250)) togetherWith fadeOut(tween(250))
    } else {
        val sign = if (forward) 1 else -1
        (slideInHorizontally(tween(400)) { sign * it } + fadeIn(tween(400))) togetherWith
            (slideOutHorizontally(tween(400)) { -sign * it } + fadeOut(tween(400)))
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
    songKey: String,
    forward: Boolean,
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
    val reduceMotion = rememberReduceMotion()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            // 24 dp embaixo: no iOS a linha do Mixer não encosta na barra de
            // abas (comparação tela a tela de 05/09).
            .padding(top = 8.dp, bottom = 24.dp)
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
            // A troca de música anima pela IDENTIDADE da música (avanço da
            // fila, pular, voltar, troca manual): avançar entra pela direita,
            // voltar pela esquerda, como qualquer player — o `songTransition`
            // do iOS. Com Reduzir Movimento vira um fade.
            // O estado leva o TÍTULO junto: assim a música que sai leva o
            // próprio nome para fora da tela, em vez de já mostrar o novo
            // (lint UnusedContentLambdaTargetStateParameter).
            AnimatedContent(
                targetState = songKey to songTitle,
                transitionSpec = songTransition(forward, reduceMotion),
                label = "stems.title",
                modifier = Modifier.align(Alignment.Center),
            ) { (_, title) ->
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    color = CzTokens.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 46.dp)
                        .testTag("stems.title"),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // O corpo é AO VIVO (onda, relógio, transporte do motor): não existe um
        // "corpo da música anterior" para desenhar enquanto ela sai, então o
        // parâmetro do estado não tem o que dizer aqui.
        @Suppress("UnusedContentLambdaTargetStateParameter")
        AnimatedContent(
            targetState = songKey,
            transitionSpec = songTransition(forward, reduceMotion),
            label = "stems.body",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { _ ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {

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
    onCreateSetlistWith: (String, RecentSong) -> Unit,
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
    var showingAllRecent by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    // O repertório aberto (folha com as músicas). Guarda o ID e não a cópia:
    // renomear ou remover música precisa refletir na folha aberta.
    var openListId by remember { mutableStateOf<String?>(null) }
    // Para quê o nome que a pessoa vai digitar serve (`NamingIntent` do iOS).
    var naming by remember { mutableStateOf<NamingIntent?>(null) }
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

    // A folha de nome do iOS (`.alert` com TextField): criar, criar já com
    // uma música, duplicar, renomear.
    naming?.let { intent ->
        NameDialog(
            title = stringResource(
                when (intent.kind) {
                    is NamingIntent.Kind.Duplicate -> R.string.cadentia_setlists_duplicate
                    is NamingIntent.Kind.Rename -> R.string.cadentia_setlists_rename
                    else -> R.string.cadentia_setlists_new
                },
            ),
            initial = intent.initialName,
            accent = accent,
            onConfirm = { name ->
                when (val kind = intent.kind) {
                    NamingIntent.Kind.Create -> onCreateSetlist(name)
                    is NamingIntent.Kind.CreateWith -> onCreateSetlistWith(name, kind.song)
                    is NamingIntent.Kind.Duplicate -> onDuplicateSetlist(kind.listId, name)
                    is NamingIntent.Kind.Rename -> onRenameSetlist(kind.listId, name)
                }
                naming = null
            },
            onDismiss = { naming = null },
        )
    }

    // Um repertório aberto: a folha com as músicas na ordem do show.
    val openList = setlists.lists.firstOrNull { it.id == openListId }
    if (openListId != null && openList == null) openListId = null
    if (openList != null) {
        SetlistDetailSheet(
            revision = revision,
            list = openList,
            accent = accent,
            recent = recent.songs,
            isReady = isReady,
            onAddSong = { onAddToSetlist(it, openList.id) },
            onPlayOrdered = { openListId = null; onPlaySetlist(openList, SetQueue.Mode.Ordered) },
            onPlayShuffled = { openListId = null; onPlaySetlist(openList, SetQueue.Mode.Shuffled) },
            onPlayFrom = { openListId = null; onPlayFromSetlist(openList, it) },
            onRemoveSong = { onRemoveFromSetlist(it, openList.id) },
            onDuplicate = { naming = NamingIntent(NamingIntent.Kind.Duplicate(openList.id), openList.name) },
            onRename = { naming = NamingIntent(NamingIntent.Kind.Rename(openList.id), openList.name) },
            onDelete = { openListId = null; onDeleteSetlist(openList.id) },
            onDismiss = { openListId = null },
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
            // adicionar (o "+" precisa de onde nascer).
            if (setlists.lists.isNotEmpty() || recent.songs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cadentia_setlists_section),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CzTokens.textTertiary,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Filled.AddCircle,
                            contentDescription = stringResource(R.string.cadentia_setlists_new),
                            tint = accent,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(20.dp)
                                .clickable { naming = NamingIntent(NamingIntent.Kind.Create) }
                                .testTag("setlists.new"),
                        )
                    }
                    if (setlists.lists.isNotEmpty()) {
                        CzCard(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                for ((listIndex, list) in setlists.lists.withIndex()) {
                                    if (listIndex > 0) HorizontalDivider(color = CzTokens.hairline)
                                    SetlistRow(
                                        revision = revision,
                                        list = list,
                                        accent = accent,
                                        tag = "setlists.row.$listIndex",
                                    ) { openListId = list.id }
                                }
                            }
                        }
                    }
                }
            }

            // ---- recentes ----
            if (recent.songs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    // Seis cabem antes de a lista virar rolagem sem fim; o resto
                    // fica atrás de UM toque, não atrás de um limite. Separar uma
                    // playlist e só alcançar as seis primeiras era metade do
                    // problema que o founder relatou.
                    val shown = if (showingAllRecent) recent.songs else recent.songs.take(COLLAPSED_RECENT)
                    CzCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            for ((index, song) in shown.withIndex()) {
                                if (index > 0) HorizontalDivider(color = CzTokens.hairline)
                                SongRow(
                                    revision = revision,
                                    song = song,
                                    ready = isReady(song),
                                    accent = accent,
                                    tag = "library.recent.$index",
                                    setlists = setlists,
                                    onOpen = { onReopen(song) },
                                    onForget = { onForget(song) },
                                    onAddTo = { listId -> onAddToSetlist(song, listId) },
                                    onNewSetlist = { naming = NamingIntent(NamingIntent.Kind.CreateWith(song)) },
                                )
                            }
                            if (recent.songs.size > COLLAPSED_RECENT) {
                                HorizontalDivider(color = CzTokens.hairline)
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
                                            stringResource(R.string.cadentia_library_show_all, recent.songs.size)
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = accent,
                                    )
                                }
                            }
                        }
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
                        // O nome do iOS (`library.local`) para o androidTest.
                        .testTag("library.local"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Smartphone, // `iphone` do iOS
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

/** O que uma folha de nome precisa saber: para quê o nome vai servir (port do `NamingIntent`). */
private class NamingIntent(val kind: Kind, val initialName: String = "") {
    sealed interface Kind {
        data object Create : Kind
        data class CreateWith(val song: RecentSong) : Kind
        data class Duplicate(val listId: String) : Kind
        data class Rename(val listId: String) : Kind
    }
}

/** O `.alert` com `TextField` do iOS: título, campo, Cancelar e Salvar. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    accent: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Diálogo é outra janela: as tags precisam virar resource-id aqui de novo.
        modifier = Modifier.exposeTestTags(),
        containerColor = CzTokens.stageTop,
        titleContentColor = CzTokens.textPrimary,
        textContentColor = CzTokens.textSecondary,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.cadentia_setlists_name_placeholder)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CzTokens.textPrimary,
                    unfocusedTextColor = CzTokens.textPrimary,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = CzTokens.hairline,
                    focusedLabelColor = accent,
                    unfocusedLabelColor = CzTokens.textTertiary,
                    cursorColor = accent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("setlists.name"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("setlists.confirm"),
            ) { Text(stringResource(R.string.cadentia_setlists_confirm), color = if (name.isNotBlank()) accent else CzTokens.textTertiary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cadentia_setlists_cancel), color = CzTokens.textSecondary)
            }
        },
    )
}

/** Uma Recente: ícone da origem, título, estado e o play quando está pronta (port do `recentRow`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    revision: Int,
    song: RecentSong,
    ready: Boolean,
    accent: Color,
    setlists: Setlists,
    tag: String,
    onOpen: () -> Unit,
    onForget: () -> Unit,
    onAddTo: (String) -> Unit,
    onNewSetlist: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                // Segurar numa recente é o gesto de "guardar num set", como o
                // `contextMenu` do iOS.
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Icon(
                imageVector = when (song.source) {
                    is RecentSong.Source.Device -> Icons.Filled.Smartphone
                    else -> Icons.Filled.Cloud
                },
                contentDescription = null,
                tint = accent,
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
            if (ready) {
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CzTokens.stageTop) {
            // Sem nenhum repertório ainda, vai DIRETO para o nome: submenu com
            // um item só é um toque a mais por nada.
            if (setlists.lists.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cadentia_setlists_add_to), color = CzTokens.textPrimary) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = accent) },
                    onClick = { menu = false; onNewSetlist() },
                )
            } else {
                for (list in setlists.lists) {
                    DropdownMenuItem(
                        text = { Text(list.name, color = CzTokens.textPrimary) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = accent) },
                        onClick = { menu = false; onAddTo(list.id) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cadentia_setlists_new), color = CzTokens.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = accent) },
                    onClick = { menu = false; onNewSetlist() },
                )
            }
            HorizontalDivider(color = CzTokens.hairline)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cadentia_library_remove_from_recent), color = CzTokens.danger) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = CzTokens.danger) },
                onClick = { menu = false; onForget() },
            )
        }
    }
}

/** Uma linha de repertório no cartão da biblioteca (port do `listRow`): nome, contagem, chevron. */
@Composable
private fun SetlistRow(revision: Int, list: Setlist, accent: Color, tag: String, onOpen: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = list.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = CzTokens.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // Contagem como número puro: legenda com plural em dez idiomas é
        // custo sem retorno quando o número sozinho já diz tudo.
        Text(
            text = "${list.songs.size}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = CzTokens.textTertiary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CzTokens.textTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Um repertório aberto — port do `SetlistDetailSheet`: tocar em ordem ou
 * embaralhado em cima de tudo, "Adicionar músicas", e as músicas na ordem do
 * show. Duplicar, renomear e apagar ficam no menu do canto.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SetlistDetailSheet(
    revision: Int,
    list: Setlist,
    accent: Color,
    recent: List<RecentSong>,
    isReady: (RecentSong) -> Boolean,
    onAddSong: (RecentSong) -> Unit,
    onPlayOrdered: () -> Unit,
    onPlayShuffled: () -> Unit,
    onPlayFrom: (RecentSong) -> Unit,
    onRemoveSong: (String) -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision // modelo mutável: sem isto o strong skipping pula a recomposição
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    if (adding) {
        AddSongsSheet(
            accent = accent,
            candidates = recent.filter { candidate -> list.songs.none { it.id == candidate.id } },
            hasRecents = recent.isNotEmpty(),
            onAdd = onAddSong,
            onDismiss = { adding = false },
        )
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CzTokens.stageTop,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .exposeTestTags()
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // A barra da folha: Fechar, o nome do set, o menu.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.cadentia_about_close),
                    fontSize = 14.sp,
                    color = accent,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .testTag("setlist.close"),
                )
                Text(
                    text = list.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = CzTokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    Icon(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = stringResource(R.string.cadentia_setlists_rename),
                        tint = accent,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(22.dp)
                            .clickable { menu = true }
                            .testTag("setlist.menu"),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CzTokens.stageTop) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cadentia_setlists_duplicate), color = CzTokens.textPrimary) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = CzTokens.textSecondary) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cadentia_setlists_rename), color = CzTokens.textPrimary) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = CzTokens.textSecondary) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cadentia_setlists_delete), color = CzTokens.danger) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = CzTokens.danger) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }

            // Os dois jeitos de tocar o set, em cima de tudo: é para isso que
            // a folha abre num dia de show.
            if (list.songs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PlayAction(
                        text = stringResource(R.string.cadentia_setlists_play_ordered),
                        icon = Icons.Filled.PlayArrow,
                        prominent = true,
                        accent = accent,
                        tag = "setlist.playOrdered",
                        modifier = Modifier.weight(1f),
                        onClick = onPlayOrdered,
                    )
                    PlayAction(
                        text = stringResource(R.string.cadentia_setlists_play_shuffled),
                        icon = Icons.Filled.Shuffle,
                        prominent = false,
                        accent = accent,
                        tag = "setlist.playShuffled",
                        modifier = Modifier.weight(1f),
                        onClick = onPlayShuffled,
                    )
                }
            }

            // O caminho EXPLÍCITO de encher o set. O toque longo nas Recentes
            // continua como atalho, mas atalho não pode ser o único caminho.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CzTokens.surface, RoundedCornerShape(CzTokens.radiusMD))
                    .clickable { adding = true }
                    .padding(horizontal = 14.dp, vertical = 13.dp)
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CzTokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = CzTokens.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }

            if (list.songs.isEmpty()) {
                Text(
                    text = stringResource(R.string.cadentia_setlists_empty),
                    fontSize = 13.sp,
                    color = CzTokens.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 16.dp).padding(horizontal = 24.dp),
                )
            } else {
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        for ((songIndex, song) in list.songs.withIndex()) {
                            if (songIndex > 0) HorizontalDivider(color = CzTokens.hairline)
                            SetlistSongRow(
                                song = song,
                                position = songIndex + 1,
                                ready = isReady(song),
                                accent = accent,
                                tag = "setlist.song.$songIndex",
                                onPlay = { onPlayFrom(song) },
                                onRemove = { onRemoveSong(song.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Tocar em ordem (cheio) e embaralhado (translúcido), como no iOS. */
@Composable
private fun PlayAction(
    text: String,
    icon: ImageVector,
    prominent: Boolean,
    accent: Color,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (prominent) CzTokens.stageBottom else accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .background(if (prominent) accent else accent.copy(alpha = 0.16f), RoundedCornerShape(CzTokens.radiusMD))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .testTag(tag),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

/** Uma música do set (port do `songRow`): a ordem de longe, o estado, o play; segurar remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetlistSongRow(
    song: RecentSong,
    position: Int,
    ready: Boolean,
    accent: Color,
    tag: String,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            // O número da ordem: num set de show, "qual é a próxima" é a
            // pergunta que a tela responde de longe.
            Text(
                text = "$position",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = CzTokens.textTertiary,
                modifier = Modifier.width(22.dp),
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
                Text(
                    text = stringResource(
                        if (ready) R.string.cadentia_library_ready_to_play else R.string.cadentia_library_will_separate_again,
                    ),
                    fontSize = 10.sp,
                    color = if (ready) accent.copy(alpha = 0.85f) else CzTokens.textTertiary,
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CzTokens.stageTop) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cadentia_setlists_remove_song), color = CzTokens.danger) },
                leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null, tint = CzTokens.danger) },
                onClick = { menu = false; onRemove() },
            )
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
                .exposeTestTags()
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
                    modifier = Modifier.fillMaxWidth().testTag("setlist.search"),
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
                for ((songIndex, song) in filtered.withIndex()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setlist.add.$songIndex")
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
    tag: String? = null,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .size(26.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
    )
}
