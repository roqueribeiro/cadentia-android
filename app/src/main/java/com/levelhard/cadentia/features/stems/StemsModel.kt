package com.levelhard.cadentia.features.stems

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.levelhard.cadentia.R
import com.levelhard.cadentia.features.library.RoqueOSAccount
import com.levelhard.cadentia.features.library.RoqueOSException
import com.levelhard.cadentia.features.library.RoqueOSLibrary
import com.levelhard.cadentia.kit.PracticeLoop
import com.levelhard.cadentia.kit.RecentSong
import com.levelhard.cadentia.kit.RecentSongs
import com.levelhard.cadentia.kit.SetQueue
import com.levelhard.cadentia.kit.Setlists
import com.levelhard.cadentia.kit.StemMixMemory
import com.levelhard.cadentia.kit.StemPipeline
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * O que a tela de Separar mostra e o trabalho que ela manda fazer — port do
 * `StemsModel.swift` (1.16), com o estado em Compose (o papel do
 * `@Observable`).
 *
 * A SEPARAÇÃO em si não existe nesta build: o modelo (103 MB no iOS, ausente
 * até do clone) não tem equivalente ONNX gerado ainda. O caminho inteiro está
 * de pé — fila, serviço de primeiro plano, pasta parcial publicada por
 * `rename`, cache que só apaga sem espaço — e para no fato, com todas as
 * letras (`cadentia.stems.modelMissing`), no mesmo lugar em que o iOS para sem
 * o `Separator.mlmodelc`. Quem já tem as quatro faixas em disco toca normal.
 */
class StemsModel(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    sealed class Phase {
        data object Empty : Phase()
        data object Preparing : Phase()
        data class Separating(val done: Int, val total: Int) : Phase()
        data object Ready : Phase()
        data class Failed(val detail: String) : Phase()

        /** Enquanto o app está separando: a tela do trabalho, e não a biblioteca nem o player. */
        val isWorking: Boolean get() = this is Preparing || this is Separating
    }

    /**
     * Uma música escolhida: de onde ler e quem ela é para o histórico e o
     * cache. `temporary` marca uma cópia que o app fez só para esta abertura
     * (um download da biblioteca em massa): o modelo a apaga depois de
     * normalizar, sucesso ou não.
     */
    data class Pick(val uri: Uri, val song: RecentSong, val temporary: Boolean = false)

    val engine = StemPlayerEngine()
    val stores = StemStores(appContext)
    val cache: StemCache get() = stores.cache
    val account: RoqueOSAccount = RoqueOSAccount.shared(appContext)
    val library = RoqueOSLibrary(account)

    var phase: Phase by mutableStateOf(Phase.Empty)
        private set
    var songTitle by mutableStateOf("")
        private set

    /**
     * Quando o trabalho atual começou. A tela estima o que falta a partir
     * daqui: o tempo por janela varia com o aparelho e com a temperatura, então
     * a única estimativa honesta é a que sai do que já aconteceu nesta execução.
     */
    var workStartedAt by mutableLongStateOf(System.currentTimeMillis())
        private set

    var recent: RecentSongs by mutableStateOf(RecentSongs())
        private set
    var setlists: Setlists by mutableStateOf(Setlists())
        private set
    private var mixMemory: StemMixMemory = StemMixMemory()
    var currentSongId: String? by mutableStateOf(null)
        private set
    var queue: SetQueue? by mutableStateOf(null)
        private set
    private var pendingAutoplay = false
    var loopAnchor: Double? by mutableStateOf(null)
        private set

    /** Item remoto baixando agora (a linha do navegador mostra que está trabalhando). */
    var downloadingId: String? by mutableStateOf(null)
        private set

    /** Sobe a cada mudança nos modelos mutáveis do Kit (a regra do `revision`). */
    var revision by mutableIntStateOf(0)
        private set

    init {
        recent = stores.loadRecent()
        setlists = stores.loadSetlists()
        mixMemory = stores.loadMixMemory()
        engine.onFinished = { advanceQueue(automatic = true) }
        SeparationService.cancelListener = { cancelBatch() }
    }

    fun shutdown() {
        persistMix()
        cancelBatch()
        engine.shutdown()
        if (SeparationService.cancelListener != null) SeparationService.cancelListener = null
        scope.cancel()
    }

    private fun bump() {
        revision++
    }

    // ── histórico e ajuste ──────────────────────────────────────────────

    private fun remember(song: RecentSong) {
        recent.remember(song.copy(lastOpenedEpochMillis = System.currentTimeMillis()))
        stores.saveRecent(recent)
        bump()
    }

    /** QA: põe uma música nas Recentes sem abrir. */
    fun rememberForQA(song: RecentSong) {
        recent.remember(song)
        stores.saveRecent(recent)
        bump()
    }

    fun persistMix() {
        val songId = currentSongId ?: return
        mixMemory.remember(engine.snapshot(), songId)
        stores.saveMixMemory(mixMemory)
    }

    private fun restoreMix(song: RecentSong) {
        mixMemory.snapshot(song.id)?.let { engine.apply(it) }
    }

    /** Músicas de algum repertório: nunca saem do disco na limpeza. */
    private val setlistSongIds: Set<String>
        get() = setlists.lists.flatMap { list -> list.songs.map { it.id } }.toSet()

    /** Varre o disco fora da thread principal: entulho de separação interrompida fora, e o espaço revisado. */
    fun sweepStorage() {
        val protected = setlistSongIds
        scope.launch(Dispatchers.IO) { cache.sweep(protected) }
    }

    /**
     * Apaga TODAS as faixas separadas. As músicas continuam nas Recentes, agora
     * "vai separar de novo". O `delete` de gigabytes fora da thread principal;
     * a tela recompõe (e remede o espaço) quando terminar.
     */
    fun clearStorage() {
        engine.stop()
        if (phase is Phase.Ready) reset()
        scope.launch {
            withContext(Dispatchers.IO) { cache.removeAll() }
            bump()
        }
    }

    fun forget(song: RecentSong) {
        cache.remove(song.id)
        recent.forget(song.id)
        stores.saveRecent(recent)
        bump()
    }

    fun isReady(song: RecentSong): Boolean = cache.isComplete(song.id)

    // ── abrir ────────────────────────────────────────────────────────────

    /**
     * Reabre uma música do histórico. Com as faixas em disco toca na hora; sem
     * elas, busca a origem de novo e separa, o que é o custo de ter apagado o
     * cache, não um erro.
     */
    fun reopen(song: RecentSong, autoplay: Boolean = false) {
        // Antes de tudo: uma leva de vinte continuando por baixo enquanto esta
        // música baixa são duas separações se encontrando.
        cancelBatch()
        pendingAutoplay = autoplay
        if (cache.isComplete(song.id)) {
            openCached(song)
            return
        }
        when (val source = song.source) {
            is RecentSong.Source.Device -> open(Pick(Uri.parse(source.persistedUri), song))
            else -> {
                songTitle = song.title
                workStartedAt = System.currentTimeMillis()
                phase = Phase.Preparing
                batchJob = scope.launch {
                    val raw = stores.cache.stagingDirectory(song.id).also { it.mkdirs() }.resolve("origem")
                    val outcome = withContext(Dispatchers.IO) { runCatching { library.refetch(song, raw) } }
                    outcome.fold(
                        onSuccess = { open(Pick(Uri.fromFile(raw), song)) },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            Log.w(TAG, "rebaixar falhou: ${error.message}")
                            phase = Phase.Failed(reason(error))
                        },
                    )
                }
            }
        }
    }

    private fun openCached(song: RecentSong) {
        if (!engine.load(cache.directory(song.id), StemPipeline.sourceNames)) {
            phase = Phase.Failed(appContext.getString(R.string.cadentia_stems_failed))
            return
        }
        cache.touch(song.id)
        remember(song)
        currentSongId = song.id
        loopAnchor = null
        restoreMix(song)
        songTitle = song.title
        phase = Phase.Ready
        if (pendingAutoplay) {
            pendingAutoplay = false
            engine.play()
        }
        bump()
    }

    /** Um item da biblioteca RoqueOS: baixa e segue o fluxo de uma música só. */
    fun openRemoteItem(item: RoqueOSLibrary.Item) {
        val song = RecentSong(
            title = item.name.substringBeforeLast('.'),
            source = item.recentSource(),
            lastOpenedEpochMillis = System.currentTimeMillis(),
        )
        cancelBatch()
        if (cache.isComplete(song.id)) {
            openCached(song)
            return
        }
        downloadingId = item.id
        songTitle = song.title
        workStartedAt = System.currentTimeMillis()
        phase = Phase.Preparing
        batchJob = scope.launch {
            try {
                val raw = cache.stagingDirectory(song.id).also { it.mkdirs() }.resolve("origem")
                withContext(Dispatchers.IO) { library.download(item, raw) }
                downloadingId = null
                open(Pick(Uri.fromFile(raw), song))
            } catch (error: CancellationException) {
                downloadingId = null
                throw error
            } catch (error: Exception) {
                downloadingId = null
                phase = Phase.Failed(reason(error))
            }
        }
    }

    /**
     * Abre uma música. Abrir é dizer "quero esta agora": uma leva em curso
     * MORRE, não continua por baixo — duas separações vivas são dois picos de
     * um gigabyte.
     */
    fun open(pick: Pick) {
        cancelBatch()
        songTitle = pick.song.title
        workStartedAt = System.currentTimeMillis()
        phase = Phase.Preparing
        // Uma música que JÁ está no cache não separa nada: a notificação
        // apareceria e sumiria no mesmo piscar, o que é pior que não aparecer.
        val needsService = !cache.isComplete(pick.song.id)
        if (needsService) beginJob(pick.song.title, appContext.getString(R.string.cadentia_stems_job_title))
        batchJob = scope.launch {
            var separated = false
            try {
                val folder = materialize(pick) { done, total ->
                    phase = Phase.Separating(done, total)
                    if (total > 0) reportJob(pick.song.title, appContext.getString(R.string.cadentia_stems_job_title), done, total)
                }
                ensureActive()
                if (!engine.load(folder, StemPipeline.sourceNames)) throw StemsError.Unsupported()
                remember(pick.song)
                currentSongId = pick.song.id
                loopAnchor = null
                restoreMix(pick.song)
                phase = Phase.Ready
                separated = true
                if (pendingAutoplay) {
                    pendingAutoplay = false
                    engine.play()
                }
                bump()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // O erro REAL, não um genérico: sem isto a falha vira "não deu"
                // e o motivo (formato, modelo ausente, download parcial) fica invisível.
                Log.w(TAG, "falhou: ${error.message}")
                phase = Phase.Failed(reason(error))
            } finally {
                if (needsService) finishJob()
            }
            if (!separated) bump()
        }
    }

    // ── uma leva de músicas de uma vez ───────────────────────────────────

    /**
     * O andamento de uma leva de músicas escolhidas juntas. Fica FORA de
     * `phase` de propósito: a primeira música já está no player enquanto as
     * outras ainda separam.
     */
    data class ImportBatch(
        val total: Int,
        val done: Int = 0,
        /** A música separando agora. */
        val title: String = "",
        val windowsDone: Int = 0,
        val windowsTotal: Int = 0,
        /** Títulos que não deram: mostrados no fim, sem derrubar o resto. */
        val failed: List<String> = emptyList(),
        /** A fila inteira, na ordem: "3 de 12" não diz QUAIS três. */
        val titles: List<String> = emptyList(),
    ) {
        val isFinished: Boolean get() = done >= total
    }

    var batch: ImportBatch? by mutableStateOf(null)
        private set
    private var batchJob: Job? = null
    private var jobActive = false

    /**
     * Abre várias músicas de uma vez. **Uma por vez, em série**: o separador
     * chega perto de um gigabyte de pico. A primeira que ficar pronta vai para
     * o player, então dá para começar a estudar enquanto o resto separa.
     */
    fun openMany(picks: List<Pick>) {
        if (picks.isEmpty()) return
        if (picks.size == 1) {
            open(picks[0])
            return
        }
        cancelBatch()
        queue = null
        pendingAutoplay = false
        songTitle = picks[0].song.title
        workStartedAt = System.currentTimeMillis()
        phase = Phase.Preparing
        batch = ImportBatch(total = picks.size, title = picks[0].song.title, titles = picks.map { it.song.title })
        beginJob(
            appContext.getString(R.string.cadentia_stems_job_title),
            appContext.getString(R.string.cadentia_stems_job_starting, picks.size),
        )

        batchJob = scope.launch {
            var playing = false
            var firstError: Exception? = null
            try {
                for (pick in picks) {
                    ensureActive()
                    val position = (batch?.done ?: 0) + 1
                    batch = batch?.copy(title = pick.song.title, windowsDone = 0, windowsTotal = 0)
                    val subtitle = appContext.getString(R.string.cadentia_stems_job_position, position, picks.size)
                    reportJob(pick.song.title, subtitle, (position - 1) * 1000, picks.size * 1000)
                    workStartedAt = System.currentTimeMillis()
                    try {
                        val folder = materialize(pick) { done, total ->
                            batch = batch?.copy(windowsDone = done, windowsTotal = total)
                            // Progresso da LEVA inteira, mil passos por música.
                            if (total > 0) {
                                reportJob(pick.song.title, subtitle, (position - 1) * 1000 + done * 1000 / total, picks.size * 1000)
                                phase = Phase.Separating(done, total)
                            }
                        }
                        ensureActive()
                        remember(pick.song)
                        // A PRIMEIRA QUE DER CERTO vai para o player, não a primeira da lista.
                        if (!playing) {
                            if (!engine.load(folder, StemPipeline.sourceNames)) throw StemsError.Unsupported()
                            playing = true
                            songTitle = pick.song.title
                            currentSongId = pick.song.id
                            loopAnchor = null
                            restoreMix(pick.song)
                            phase = Phase.Ready
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "leva: ${pick.song.title} falhou — ${error.message}")
                        if (firstError == null) firstError = error
                        batch = batch?.let { it.copy(failed = it.failed + pick.song.title) }
                    }
                    batch = batch?.let { it.copy(done = it.done + 1) }
                    // Fecha a música no progresso do sistema: uma leva de músicas
                    // que JÁ estavam separadas não reporta janela nenhuma.
                    reportJob(pick.song.title, subtitle, (batch?.done ?: 0) * 1000, picks.size * 1000)
                    bump()
                }
                // O MOTIVO, não o nome da música.
                if (!playing) phase = Phase.Failed(firstError?.let { reason(it) } ?: "")
            } finally {
                finishJob()
                // A leva acabou: a sessão do modelo vai embora com ela. Medido
                // no emulador (04/09): o processo fica em 1,5 GB de RSS com a
                // sessão aberta depois de separar (pesos + arena do ORT), e é
                // esse tamanho que o lowmemorykiller escolhe primeiro quando o
                // app vai para trás tocando. Reabrir custa ~2,5 s na próxima leva.
                releaseSeparator()
            }
            // Terminou sem tropeço: o aviso some sozinho. Com falha, fica até alguém ler.
            if (batch?.failed?.isEmpty() == true) {
                delay(2000)
                if (batch?.isFinished == true) batch = null
            }
            bump()
        }
    }

    /**
     * Mata a leva em curso: a corrotina, o serviço e o aviso na tela. Chamado
     * de todo lugar que significa "quero outra coisa agora".
     */
    fun cancelBatch() {
        batchJob?.cancel()
        batchJob = null
        finishJob()
        batch = null
        downloadingId = null
    }

    /** O botão do aviso: cancela se ainda está rodando, dispensa se acabou. */
    fun dismissBatch() = cancelBatch()

    private fun beginJob(title: String, subtitle: String) {
        jobActive = true
        SeparationService.update(appContext, title, subtitle, 0, 0)
    }

    private fun reportJob(title: String, subtitle: String, done: Int, total: Int) {
        if (!jobActive) return
        SeparationService.update(appContext, title, subtitle, done, total)
    }

    private fun finishJob() {
        if (!jobActive) return
        jobActive = false
        SeparationService.stop(appContext)
    }

    /**
     * Onde as faixas de uma música estão — separando se ainda não estiverem.
     * Não toca no player nem decide o `phase` final: quem chamou é que sabe.
     *
     * Escreve em UM lugar (`<id>.parcial`) e publica no outro com um `rename`:
     * ou a pasta está inteira, ou não está.
     */
    private suspend fun materialize(pick: Pick, progress: (Int, Int) -> Unit): File {
        try {
            return materializeUnchecked(pick, progress)
        } finally {
            // A cópia temporária (download em massa) já cumpriu o papel dela,
            // deu certo ou não. Fora do cancelamento: é um `delete`, não espera.
            if (pick.temporary) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    pick.uri.path?.let { File(it).delete() }
                }
            }
        }
    }

    private suspend fun materializeUnchecked(pick: Pick, progress: (Int, Int) -> Unit): File {
        val song = pick.song
        if (cache.isComplete(song.id)) {
            cache.touch(song.id)
            return cache.directory(song.id)
        }
        val staging = cache.stagingDirectory(song.id)
        try {
            // FORA da thread principal: a normalização decodifica e reamostra a
            // música inteira. Numa leva de vinte, com a primeira tocando, um
            // travamento aqui apareceria como onda parada.
            val prepared = withContext(Dispatchers.IO) {
                staging.mkdirs()
                val target = staging.resolve("entrada.wav")
                if (!StemAudioNormalizer.normalize(appContext, pick.uri, target)) throw StemsError.Unsupported()
                // Entrada sem áudio nenhum (download parcial, arquivo corrompido)
                // sairia como quatro faixas vazias que o cache aceita como prontas.
                if (target.length() <= 44) throw StemsError.Unsupported()
                target
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            separate(prepared, staging, progress)
            withContext(Dispatchers.IO) {
                prepared.delete()
                staging.resolve("origem").delete()
                if (!cache.publish(song.id)) throw StemsError.Unsupported()
                // Só quando o aparelho pede; a recém-escrita nunca sai (ver a regra).
                cache.trim(setlistSongIds)
            }
            return cache.directory(song.id)
        } catch (error: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { staging.deleteRecursively() }
            throw error
        }
    }

    /**
     * A separação em si — o `separateToFiles` do iOS, com o modelo em ONNX
     * Runtime. Sem o modelo no aparelho, para no fato: o mesmo `modelMissing`
     * que o iOS mostra sem o `Separator.mlmodelc`. A sessão do modelo fica
     * aberta entre as músicas de uma leva (abrir custa segundos e 174 MB de
     * pesos) e fecha com a tela.
     */
    private suspend fun separate(input: File, into: File, progress: (Int, Int) -> Unit) {
        if (!StemModelStore.isAvailable(appContext)) {
            if (!StemModelDownloader.isConfigured) throw StemsError.ModelMissing()
            // Primeira separação: o modelo desce antes, com progresso na tela.
            withContext(Dispatchers.IO) {
                try {
                    StemModelDownloader.ensure(appContext, shouldContinue = { isActive }) { bytes, total ->
                        modelDownload = bytes to total
                    }
                } catch (error: StemModelDownloader.DownloadFailed) {
                    throw StemsError.ModelDownloadFailed(error.message ?: "download")
                } catch (_: InterruptedException) {
                    throw CancellationException("download cancelado")
                } finally {
                    modelDownload = null
                }
            }
        }
        withContext(Dispatchers.Default) {
            val backend = separatorBackend ?: OnnxStemBackend(StemModelStore.file(appContext)).also { separatorBackend = it }
            try {
                StemSeparator.separate(backend, input, into, progress) { isActive }
            } catch (_: InterruptedException) {
                throw CancellationException("separação cancelada")
            }
        }
    }

    private var separatorBackend: OnnxStemBackend? = null

    /** Bytes baixados e total (−1 = desconhecido) enquanto o modelo desce; null fora disso. */
    var modelDownload: Pair<Long, Long>? by mutableStateOf(null)
        private set

    /** Solta o modelo (174 MB de pesos) quando a área de stems é deixada. */
    private fun releaseSeparator() {
        separatorBackend?.let { runCatching { it.close() } }
        separatorBackend = null
    }

    // ── voltar, fila do repertório, loop ─────────────────────────────────

    /** Volta para a biblioteca. Sair da tela mata a leva: ela continuando invisível é o caminho para duas separações. */
    fun reset() {
        cancelBatch()
        persistMix()
        engine.stop()
        releaseSeparator()
        songTitle = ""
        phase = Phase.Empty
        currentSongId = null
        loopAnchor = null
        queue = null
        pendingAutoplay = false
        bump()
    }

    fun playSetlist(list: com.levelhard.cadentia.kit.Setlist, mode: SetQueue.Mode) {
        SetQueue.of(list, mode)?.let { built ->
            queue = built
            built.current?.let { reopen(it, autoplay = true) }
        }
    }

    fun playFromSetlist(list: com.levelhard.cadentia.kit.Setlist, song: RecentSong) {
        SetQueue.of(list, SetQueue.Mode.Ordered, startAt = song.id)?.let { built ->
            queue = built
            built.current?.let { reopen(it, autoplay = true) }
        }
    }

    fun advanceQueue(automatic: Boolean = false) {
        val active = queue ?: return
        persistMix()
        val next = active.advance()
        if (next != null) {
            reopenKeepingQueue(next)
        } else if (!automatic) {
            queue = null
        }
        bump()
    }

    fun goBackInQueue() {
        val active = queue ?: return
        persistMix()
        active.goBack()?.let { reopenKeepingQueue(it) }
        bump()
    }

    /** Dentro de um repertório a fila sobrevive à troca de música. */
    private fun reopenKeepingQueue(song: RecentSong) {
        val keep = queue
        reopen(song, autoplay = true)
        queue = keep
    }

    /** O loop A/B em três toques: marca A, marca B (liga), limpa. */
    fun cycleLoop() {
        if (engine.practiceLoop != null) {
            engine.practiceLoop = null
            loopAnchor = null
            persistMix()
            bump()
            return
        }
        val anchor = loopAnchor
        if (anchor != null) {
            PracticeLoop.of(anchor, engine.currentTime)?.let { loop ->
                engine.practiceLoop = loop.clamped(engine.duration)
                loopAnchor = null
                persistMix()
            }
            bump()
            return
        }
        loopAnchor = engine.currentTime
        bump()
    }

    // ── repertórios ──────────────────────────────────────────────────────

    fun createSetlist(name: String) {
        setlists.create(name)
        stores.saveSetlists(setlists)
        bump()
    }

    fun addToSetlist(song: RecentSong, listId: String) {
        setlists.add(song, listId)
        stores.saveSetlists(setlists)
        bump()
    }

    fun removeFromSetlist(songId: String, listId: String) {
        setlists.removeSong(songId, listId)
        stores.saveSetlists(setlists)
        bump()
    }

    fun duplicateSetlist(listId: String, name: String) {
        setlists.duplicate(listId, name)
        stores.saveSetlists(setlists)
        bump()
    }

    fun renameSetlist(listId: String, name: String) {
        setlists.rename(listId, name)
        stores.saveSetlists(setlists)
        bump()
    }

    fun deleteSetlist(listId: String) {
        setlists.delete(listId)
        stores.saveSetlists(setlists)
        bump()
    }

    // ── QA ───────────────────────────────────────────────────────────────

    /**
     * QA: congela a tela de separação num ponto do progresso. Existe porque a
     * separação de verdade não roda sem o modelo — sem isto, o único jeito de
     * olhar esta tela seria um print no meio de uma separação no aparelho.
     */
    fun showSeparatingForQA(title: String, done: Int, total: Int, startedAgoSeconds: Double, batchOf: Int? = null) {
        songTitle = title
        workStartedAt = System.currentTimeMillis() - (startedAgoSeconds * 1000).toLong()
        if (batchOf != null && batchOf > 1) {
            val names = QA_TITLES
            val current = minOf(3, batchOf - 1)
            batch = ImportBatch(
                total = batchOf, done = current, title = names[current % names.size],
                windowsDone = done, windowsTotal = total,
                titles = (0 until batchOf).map { names[it % names.size] },
            )
            songTitle = batch?.title ?: title
        }
        phase = if (done <= 0) Phase.Preparing else Phase.Separating(done, total)
    }

    /**
     * QA: a faixa da leva por cima da biblioteca, como fica depois de uma leva
     * de `total` músicas terminar com `failed` que não deram. Com falha ela
     * fica até alguém dispensar, e é esse o estado que precisa de print.
     */
    fun showBatchBannerForQA(total: Int, failed: Int) {
        val names = QA_TITLES
        val titles = (0 until total).map { names[it % names.size] }
        batch = ImportBatch(
            total = total, done = total, title = titles.lastOrNull() ?: "",
            failed = titles.takeLast(failed.coerceIn(0, total)), titles = titles,
        )
    }

    private fun reason(error: Throwable): String = when (error) {
        is StemsError.ModelMissing -> appContext.getString(R.string.cadentia_stems_model_missing)
        is StemsError.ModelDownloadFailed -> appContext.getString(R.string.cadentia_stems_model_download_failed)
        is StemsError.Unsupported -> appContext.getString(R.string.cadentia_stems_failed)
        is RoqueOSException -> error.display
        else -> error.message ?: error.javaClass.simpleName
    }

    companion object {
        private const val TAG = "CadentiaStems"
        val QA_TITLES = listOf(
            "Bohemian Rhapsody", "Aquarela do Brasil", "Garota de Ipanema", "Construção", "Chega de Saudade",
            "Trem das Onze", "Asa Branca", "O Trenzinho do Caipira", "Carinhoso", "Wave", "Corcovado",
            "Samba de Uma Nota Só",
        )
    }
}

/** Os motivos de a separação parar, para a tela dizer o certo. */
sealed class StemsError(message: String) : Exception(message) {
    class ModelMissing : StemsError("modelo de separação ausente")
    class ModelDownloadFailed(detail: String) : StemsError("download do modelo: $detail")
    class Unsupported : StemsError("áudio não suportado")
}
