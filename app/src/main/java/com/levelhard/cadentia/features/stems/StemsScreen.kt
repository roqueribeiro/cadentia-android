package com.levelhard.cadentia.features.stems

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.PracticeLoop
import com.levelhard.cadentia.kit.RecentSong
import com.levelhard.cadentia.kit.RecentSongs
import com.levelhard.cadentia.kit.SetQueue
import com.levelhard.cadentia.kit.Setlist
import com.levelhard.cadentia.kit.Setlists
import com.levelhard.cadentia.kit.SongSearch
import com.levelhard.cadentia.kit.StemMixMemory
import com.levelhard.cadentia.kit.StemPipeline
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Separar — port do `StemsView.swift` + `StemsModel.swift`: biblioteca
 * (recentes com busca, repertórios com fila em ordem/aleatório), player das
 * quatro faixas com onda viva, loop A/B em um botão, mixer em folha e
 * memória de ajuste por música.
 *
 * A SEPARAÇÃO em si não existe nesta build: o modelo (103 MB no iOS,
 * ausente até do clone) não tem equivalente ONNX gerado ainda. Abrir uma
 * música nova normaliza o arquivo e diz isso com todas as letras
 * (cadentia.stems.modelMissing) — o MESMO estado do iOS sem o
 * Separator.mlmodelc. Quem já tem as quatro faixas em disco toca normal.
 */
@Composable
fun StemsScreen() {
    val accent = CzTokens.stemsTeal
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engine = remember { StemPlayerEngine() }
    val stores = remember { StemStores(context.applicationContext) }
    val roqueAccount = remember {
        com.levelhard.cadentia.features.library.RoqueOSAccount.shared(context.applicationContext)
    }
    val roqueLibrary = remember {
        com.levelhard.cadentia.features.library.RoqueOSLibrary(roqueAccount)
    }
    var downloadingId by remember { mutableStateOf<String?>(null) }

    var phase by remember { mutableStateOf<Phase>(Phase.Empty) }
    var songTitle by remember { mutableStateOf("") }
    var currentSongId by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(RecentSongs()) }
    var setlists by remember { mutableStateOf(Setlists()) }
    var mixMemory by remember { mutableStateOf(StemMixMemory()) }
    var queue by remember { mutableStateOf<SetQueue?>(null) }
    var pendingAutoplay by remember { mutableStateOf(false) }
    var loopAnchor by remember { mutableStateOf<Double?>(null) }
    var showMixer by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var playhead by remember { mutableDoubleStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }

    fun persistMix() {
        val songId = currentSongId ?: return
        mixMemory.remember(engine.snapshot(), songId)
        stores.saveMixMemory(mixMemory)
    }

    fun rememberSong(song: RecentSong) {
        recent.remember(song.copy(lastOpenedEpochMillis = System.currentTimeMillis()))
        stores.saveRecent(recent)
        revision++
    }

    fun openCached(song: RecentSong) {
        if (!engine.load(stores.cacheDirectory(song.id), StemPipeline.sourceNames)) {
            phase = Phase.Failed(context.getString(R.string.cadentia_stems_model_missing))
            return
        }
        stores.touchCache(song.id)
        rememberSong(song)
        currentSongId = song.id
        loopAnchor = null
        mixMemory.snapshot(song.id)?.let { engine.apply(it) }
        songTitle = song.title
        phase = Phase.Ready
        playhead = 0.0
        if (pendingAutoplay) {
            pendingAutoplay = false
            engine.play()
            isPlaying = true
        }
        revision++
    }

    /**
     * Música nova: normaliza e PARA no fato — sem modelo de separação nesta
     * build, o resto do caminho não existe.
     */
    fun openFresh(uri: Uri, song: RecentSong) {
        songTitle = song.title
        phase = Phase.Preparing
        scope.launch {
            val normalized = withContext(Dispatchers.IO) {
                val target = stores.cacheDirectory(song.id).resolve("entrada.wav")
                StemAudioNormalizer.normalize(context, uri, target)
            }
            phase = if (!normalized) {
                Phase.Failed(context.getString(R.string.cadentia_stems_failed))
            } else {
                rememberSong(song)
                Phase.Failed(context.getString(R.string.cadentia_stems_model_missing))
            }
        }
    }

    /** Música remota sem cache: rebaixa a origem, normaliza e diz o fato. */
    fun openRemoteFresh(song: RecentSong) {
        songTitle = song.title
        phase = Phase.Preparing
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = stores.cacheDirectory(song.id).resolve("origem-${'$'}{song.id}")
                    roqueLibrary.refetch(song, raw)
                    val normalized = StemAudioNormalizer.normalize(
                        context, Uri.fromFile(raw), stores.cacheDirectory(song.id).resolve("entrada.wav"),
                    )
                    raw.delete()
                    normalized
                }
            }
            phase = outcome.fold(
                onSuccess = { ok ->
                    if (ok) {
                        rememberSong(song)
                        Phase.Failed(context.getString(R.string.cadentia_stems_model_missing))
                    } else {
                        Phase.Failed(context.getString(R.string.cadentia_stems_failed))
                    }
                },
                onFailure = { error ->
                    val display = (error as? com.levelhard.cadentia.features.library.RoqueOSException)
                        ?.display ?: (error.message ?: error.javaClass.simpleName)
                    Phase.Failed(display)
                },
            )
        }
    }

    fun open(song: RecentSong, autoplay: Boolean = false) {
        pendingAutoplay = autoplay
        val source = song.source
        if (stores.isCacheComplete(song.id)) {
            openCached(song)
        } else if (source is RecentSong.Source.Device) {
            openFresh(Uri.parse(source.persistedUri), song)
        } else {
            // Fonte remota: rebaixar de novo é o custo de ter perdido o
            // cache, não um erro.
            openRemoteFresh(song)
        }
    }

    /** Um item baixado da biblioteca RoqueOS vira Recente e segue o fluxo. */
    fun openRemoteItem(item: com.levelhard.cadentia.features.library.RoqueOSLibrary.Item) {
        val song = RecentSong(
            title = item.name.substringBeforeLast('.'),
            source = item.recentSource(),
            lastOpenedEpochMillis = System.currentTimeMillis(),
        )
        if (stores.isCacheComplete(song.id)) {
            openCached(song)
            return
        }
        downloadingId = item.id
        songTitle = song.title
        phase = Phase.Preparing
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = stores.cacheDirectory(song.id).resolve("origem-${'$'}{song.id}")
                    roqueLibrary.download(item, raw)
                    val normalized = StemAudioNormalizer.normalize(
                        context, Uri.fromFile(raw), stores.cacheDirectory(song.id).resolve("entrada.wav"),
                    )
                    raw.delete()
                    normalized
                }
            }
            downloadingId = null
            phase = outcome.fold(
                onSuccess = { ok ->
                    if (ok) {
                        rememberSong(song)
                        Phase.Failed(context.getString(R.string.cadentia_stems_model_missing))
                    } else {
                        Phase.Failed(context.getString(R.string.cadentia_stems_failed))
                    }
                },
                onFailure = { error ->
                    val display = (error as? com.levelhard.cadentia.features.library.RoqueOSException)
                        ?.display ?: (error.message ?: error.javaClass.simpleName)
                    Phase.Failed(display)
                },
            )
        }
    }

    fun reset() {
        persistMix()
        engine.stop()
        isPlaying = false
        songTitle = ""
        phase = Phase.Empty
        currentSongId = null
        loopAnchor = null
        queue = null
        pendingAutoplay = false
        revision++
    }

    fun advanceQueue(automatic: Boolean = false) {
        val active = queue ?: return
        persistMix()
        val next = active.advance()
        if (next != null) {
            open(next, autoplay = true)
        } else if (!automatic) {
            // Pular além do fim é pedido explícito de sair do set.
            queue = null
        }
        revision++
    }

    fun goBackInQueue() {
        val active = queue ?: return
        persistMix()
        active.goBack()?.let { open(it, autoplay = true) }
        revision++
    }

    /** O loop A/B em três toques: marca A, marca B (liga), limpa. */
    fun cycleLoop() {
        if (engine.practiceLoop != null) {
            engine.practiceLoop = null
            loopAnchor = null
            persistMix()
            revision++
            return
        }
        val anchor = loopAnchor
        if (anchor != null) {
            PracticeLoop.of(anchor, engine.currentTime)?.let { loop ->
                engine.practiceLoop = loop.clamped(engine.duration)
                loopAnchor = null
                persistMix()
            }
            revision++
            return
        }
        loopAnchor = engine.currentTime
        revision++
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: context.getString(R.string.cadentia_stems_title)
        val song = RecentSong(
            title = name.substringBeforeLast('.'),
            source = RecentSong.Source.Device(persistedUri = uri.toString(), filename = name),
            lastOpenedEpochMillis = System.currentTimeMillis(),
        )
        open(song)
    }

    LaunchedEffect(Unit) {
        recent = stores.loadRecent()
        setlists = stores.loadSetlists()
        mixMemory = stores.loadMixMemory()
        engine.onFinished = { advanceQueue(automatic = true) }
        revision++
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
        onDispose {
            persistMix()
            engine.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        @Suppress("UNUSED_EXPRESSION") revision
        when (val current = phase) {
            Phase.Ready -> PlayerState(
                engine = engine,
                accent = accent,
                songTitle = songTitle,
                queue = queue,
                playhead = playhead,
                isPlaying = isPlaying,
                loopAnchor = loopAnchor,
                revision = revision,
                onBack = { reset() },
                onPlayPause = {
                    if (engine.isPlaying) {
                        engine.pause()
                        persistMix()
                    } else {
                        engine.play()
                    }
                    isPlaying = engine.isPlaying
                },
                onSeek = { engine.seek(it) },
                onSkip = { engine.seek(engine.currentTime + it) },
                onLoop = { cycleLoop() },
                onMixer = { showMixer = true },
                onQueuePrev = { goBackInQueue() },
                onQueueNext = { advanceQueue() },
            )
            else -> LibraryState(
                accent = accent,
                phase = current,
                songTitle = songTitle,
                recent = recent,
                setlists = setlists,
                isReady = { stores.isCacheComplete(it.id) },
                revision = revision,
                roqueSection = {
                    com.levelhard.cadentia.features.library.RoqueOSSection(
                        accent = accent,
                        account = roqueAccount,
                        onPick = { openRemoteItem(it) },
                        downloadingId = downloadingId,
                    )
                },
                onOpenFile = { importer.launch(arrayOf("audio/*")) },
                onReopen = { open(it) },
                onForget = { song ->
                    stores.removeCache(song.id)
                    recent.forget(song.id)
                    stores.saveRecent(recent)
                    revision++
                },
                onTryAgain = { reset() },
                onCreateSetlist = { name ->
                    setlists.create(name)
                    stores.saveSetlists(setlists)
                    revision++
                },
                onAddToSetlist = { song, listId ->
                    setlists.add(song, listId)
                    stores.saveSetlists(setlists)
                    revision++
                },
                onRemoveFromSetlist = { songId, listId ->
                    setlists.removeSong(songId, listId)
                    stores.saveSetlists(setlists)
                    revision++
                },
                onDuplicateSetlist = { listId, name ->
                    setlists.duplicate(listId, name)
                    stores.saveSetlists(setlists)
                    revision++
                },
                onRenameSetlist = { listId, name ->
                    setlists.rename(listId, name)
                    stores.saveSetlists(setlists)
                    revision++
                },
                onDeleteSetlist = { listId ->
                    setlists.delete(listId)
                    stores.saveSetlists(setlists)
                    revision++
                },
                onPlaySetlist = { list, mode ->
                    SetQueue.of(list, mode)?.let { built ->
                        queue = built
                        built.current?.let { open(it, autoplay = true) }
                    }
                },
                onPlayFromSetlist = { list, song ->
                    SetQueue.of(list, SetQueue.Mode.Ordered, startAt = song.id)?.let { built ->
                        queue = built
                        built.current?.let { open(it, autoplay = true) }
                    }
                },
            )
        }
    }

    if (showMixer) {
        StemMixerSheet(
            engine = engine,
            accent = accent,
            revision = revision,
            onDismiss = {
                showMixer = false
                persistMix()
            },
            onChanged = {
                persistMix()
                revision++
            },
        )
    }
}

private sealed class Phase {
    data object Empty : Phase()
    data object Preparing : Phase()
    data object Ready : Phase()
    data class Failed(val detail: String) : Phase()
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
    phase: Phase,
    songTitle: String,
    recent: RecentSongs,
    setlists: Setlists,
    isReady: (RecentSong) -> Boolean,
    revision: Int,
    roqueSection: @Composable () -> Unit,
    onOpenFile: () -> Unit,
    onReopen: (RecentSong) -> Unit,
    onForget: (RecentSong) -> Unit,
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
    var search by remember { mutableStateOf("") }
    var newListName by remember { mutableStateOf("") }

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
            when (phase) {
                Phase.Preparing -> CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .testTag("stems.working"),
                    ) {
                        CircularProgressIndicator(color = accent)
                        Text(
                            text = stringResource(R.string.cadentia_stems_preparing),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = CzTokens.textSecondary,
                        )
                        Text(
                            text = songTitle,
                            fontSize = 12.sp,
                            maxLines = 1,
                            color = CzTokens.textTertiary,
                        )
                    }
                }
                is Phase.Failed -> CzCard(modifier = Modifier.fillMaxWidth()) {
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
                        Text(
                            text = phase.detail,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            color = CzTokens.textTertiary,
                            modifier = Modifier.testTag("stems.failureReason"),
                        )
                        Text(
                            text = stringResource(R.string.cadentia_stems_try_again),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            modifier = Modifier.clickable(onClick = onTryAgain),
                        )
                    }
                }
                else -> CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .background(accent, RoundedCornerShape(50))
                                .clickable(onClick = onOpenFile)
                                .padding(horizontal = 18.dp, vertical = 11.dp)
                                .testTag("stems.choose"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = stringResource(R.string.cadentia_stems_choose),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black,
                            )
                        }
                    }
                }
            }

            // ---- RoqueOS ----
            roqueSection()

            // ---- recentes ----
            if (recent.songs.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.cadentia_library_recent),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CzTokens.textTertiary,
                )
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
                for (song in filtered) {
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
            }

            // ---- repertórios ----
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
    }
}

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
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = if (ready) accent else CzTokens.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = song.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = CzTokens.textPrimary,
            modifier = Modifier.weight(1f),
        )
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
