package com.levelhard.cadentia.kit

import java.util.UUID
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Projeto multitrack: trilhas feitas de clipes sobre uma linha do tempo —
 * port 1:1 do `RecorderProject.swift`.
 *
 * O modelo anterior era uma lista chata de trilhas, cada uma presa a um
 * arquivo que sempre começava no zero. Isso é um gravador de fita, não uma
 * DAW: nada podia ser movido, aparado, dividido ou reusado. Aqui a trilha
 * tem quantos clipes quiser, e um clipe é uma JANELA sobre um arquivo
 * (`trimStart` + `duration`) posta num ponto da linha do tempo (`start`).
 * Dividir é dois clipes sobre um arquivo, mover é um número, aparar nunca
 * toca na gravação — é o que torna a edição não destrutiva e o undo barato.
 *
 * Tudo aqui é puro: as operações de edição são testáveis sem motor de áudio.
 */
@Serializable
data class RecorderProject(
    var tracks: MutableList<Track> = mutableListOf(),
    var bpm: Int = 100,
    var metronomeEnabled: Boolean = false,
    var countInBars: Int = 1,
) {
    @Serializable
    data class Clip(
        var id: String = UUID.randomUUID().toString(),
        /** Nome do arquivo de áudio dentro da pasta do projeto. */
        var fileName: String,
        /** Onde o clipe começa na linha do tempo. */
        var start: Double = 0.0,
        /** Offset dentro do arquivo de origem onde o clipe começa a ler. */
        var trimStart: Double = 0.0,
        /** Comprimento audível. `trimStart + duration` nunca passa da origem. */
        var duration: Double,
        /** Comprimento total do arquivo de origem, o teto do aparo. */
        var sourceDuration: Double,
        var gain: Double = 1.0,
        var fadeIn: Double = 0.0,
        var fadeOut: Double = 0.0,
    ) {
        val end: Double get() = start + duration

        /** Envelope de ganho num ponto dentro do clipe, fades inclusos. */
        fun envelope(atClipTime: Double): Double {
            if (duration <= 0) return 0.0
            var level = gain
            if (fadeIn > 0 && atClipTime < fadeIn) {
                level *= maxOf(0.0, atClipTime / fadeIn)
            }
            if (fadeOut > 0 && atClipTime > duration - fadeOut) {
                level *= maxOf(0.0, (duration - atClipTime) / fadeOut)
            }
            return level
        }
    }

    @Serializable
    data class Track(
        var id: String = UUID.randomUUID().toString(),
        var name: String,
        /**
         * `@Required`: a chave `clips` é o que separa o formato novo do
         * legado no decode — um projeto antigo (uma faixa = um arquivo) não
         * a tem e precisa cair na migração, não passar como projeto vazio.
         */
        @Required var clips: MutableList<Clip> = mutableListOf(),
        var volume: Double = 1.0,
        /** -1 todo à esquerda até +1 todo à direita. */
        var pan: Double = 0.0,
        var muted: Boolean = false,
        var soloed: Boolean = false,
        /** Trilhas armadas recebem o próximo take. */
        var armed: Boolean = false,
        /** Índice na paleta de cores de trilha da UI. */
        var colorIndex: Int = 0,
    ) {
        val duration: Double get() = clips.maxOfOrNull { it.end } ?: 0.0
    }

    /** Comprimento do arranjo inteiro. */
    val duration: Double get() = tracks.maxOfOrNull { it.duration } ?: 0.0

    /** Quais trilhas são audíveis sob as regras de mute/solo. */
    fun audibleTracks(): List<Track> {
        val anySolo = tracks.any { it.soloed }
        return tracks.filter { !it.muted && (!anySolo || it.soloed) }
    }

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun clip(id: String): Pair<String, Clip>? {
        for (track in tracks) {
            track.clips.firstOrNull { it.id == id }?.let { return track.id to it }
        }
        return null
    }

    /** Todo arquivo ainda referenciado por algum clipe; o resto é lixo de take apagado. */
    fun referencedFiles(): Set<String> =
        tracks.flatMap { track -> track.clips.map { it.fileName } }.toSet()

    // ---- edições ----

    fun addTrack(name: String): Track {
        val track = Track(
            name = name, armed = tracks.isEmpty(), colorIndex = tracks.size % COLOR_COUNT,
        )
        tracks.add(track)
        return track
    }

    fun removeTrack(id: String) {
        tracks.removeAll { it.id == id }
    }

    fun updateTrack(id: String, apply: (Track) -> Unit) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        apply(track)
        sanitize()
    }

    fun updateClip(id: String, apply: (Clip) -> Unit) {
        for (track in tracks) {
            val clip = track.clips.firstOrNull { it.id == id } ?: continue
            apply(clip)
            sanitize()
            return
        }
    }

    fun addClip(clip: Clip, toTrack: String) {
        val track = tracks.firstOrNull { it.id == toTrack } ?: return
        track.clips.add(clip)
        sanitize()
    }

    fun removeClip(id: String) {
        for (track in tracks) track.clips.removeAll { it.id == id }
    }

    /**
     * Divide um clipe numa posição absoluta da linha do tempo. As duas
     * metades continuam apontando para o mesmo arquivo, só que em offsets
     * diferentes: dividir não custa nada e não perde nada.
     */
    fun splitClip(id: String, at: Double): Boolean {
        for (track in tracks) {
            val clipIndex = track.clips.indexOfFirst { it.id == id }
            if (clipIndex < 0) continue
            val clip = track.clips[clipIndex]
            // A divisão precisa de áudio de verdade dos dois lados do corte.
            if (at <= clip.start + 0.02 || at >= clip.end - 0.02) return false

            val offset = at - clip.start
            val left = clip.copy(
                duration = offset,
                fadeOut = minOf(clip.fadeOut, offset),
            )
            val right = clip.copy(
                id = UUID.randomUUID().toString(),
                start = at,
                trimStart = clip.trimStart + offset,
                duration = clip.duration - offset,
            )
            right.fadeIn = minOf(clip.fadeIn, right.duration)

            track.clips[clipIndex] = left
            track.clips.add(clipIndex + 1, right)
            sanitize()
            return true
        }
        return false
    }

    fun duplicateClip(id: String): String? {
        for (track in tracks) {
            val clipIndex = track.clips.indexOfFirst { it.id == id }
            if (clipIndex < 0) continue
            val original = track.clips[clipIndex]
            val copy = original.copy(id = UUID.randomUUID().toString(), start = original.end)
            track.clips.add(clipIndex + 1, copy)
            sanitize()
            return copy.id
        }
        return null
    }

    /** Move um clipe para outra trilha, mantendo a posição no tempo. */
    fun moveClip(id: String, toTrack: String) {
        val source = tracks.firstOrNull { track -> track.clips.any { it.id == id } } ?: return
        val destination = tracks.firstOrNull { it.id == toTrack } ?: return
        if (source === destination) return
        val clipIndex = source.clips.indexOfFirst { it.id == id }
        if (clipIndex < 0) return
        val clip = source.clips.removeAt(clipIndex)
        destination.clips.add(clip)
        sanitize()
    }

    // ---- invariantes ----

    /**
     * Prende tudo que um arrasto ruim, um arquivo editado à mão ou uma
     * versão antiga poderia pôr fora de faixa, e mantém os clipes em ordem.
     */
    fun sanitize() {
        bpm = bpm.coerceIn(40, 240)
        countInBars = countInBars.coerceIn(0, 4)
        for (track in tracks) {
            track.volume = track.volume.coerceIn(0.0, 1.5)
            track.pan = track.pan.coerceIn(-1.0, 1.0)
            track.colorIndex = kotlin.math.abs(track.colorIndex) % COLOR_COUNT

            for (clip in track.clips) {
                clip.start = maxOf(0.0, clip.start)
                clip.sourceDuration = maxOf(0.0, clip.sourceDuration)
                clip.trimStart = maxOf(0.0, clip.trimStart).coerceAtMost(clip.sourceDuration)
                val available = maxOf(0.0, clip.sourceDuration - clip.trimStart)
                clip.duration = maxOf(0.02, clip.duration).coerceAtMost(maxOf(0.02, available))
                clip.gain = clip.gain.coerceIn(0.0, 2.0)
                // Fades não podem se sobrepor, ou o envelope cruza o zero no
                // meio do clipe e o áudio some.
                clip.fadeIn = maxOf(0.0, clip.fadeIn).coerceAtMost(clip.duration)
                clip.fadeOut = maxOf(0.0, clip.fadeOut).coerceAtMost(clip.duration - clip.fadeIn)
            }
            track.clips.sortBy { it.start }
        }
    }

    // ---- persistência ----

    fun serialized(): String = json.encodeToString(serializer(), this)

    /** O formato que este arquivo tinha antes de clipes existirem. Só decode. */
    @Serializable
    private data class LegacyTrack(
        val id: String,
        val name: String,
        val fileName: String,
        val volume: Double,
        val muted: Boolean,
        val soloed: Boolean,
        val durationSeconds: Double,
    )

    @Serializable
    private data class LegacyProject(val tracks: List<LegacyTrack>)

    companion object {
        const val COLOR_COUNT = 6

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun load(from: String): RecorderProject {
            runCatching { json.decodeFromString(serializer(), from) }
                .getOrNull()?.let { project ->
                    project.sanitize()
                    return project
                }
            // Projetos escritos antes de clipes existirem: um arquivo por
            // trilha, sempre no zero. Erguê-los para o formato novo em vez de
            // mostrar uma sessão vazia onde os takes moravam.
            runCatching { json.decodeFromString(LegacyProject.serializer(), from) }
                .getOrNull()?.let { legacy ->
                    val project = RecorderProject()
                    project.tracks = legacy.tracks.mapIndexed { index, old ->
                        Track(
                            id = old.id,
                            name = old.name,
                            clips = mutableListOf(
                                Clip(
                                    fileName = old.fileName,
                                    duration = maxOf(0.02, old.durationSeconds),
                                    sourceDuration = maxOf(0.02, old.durationSeconds),
                                ),
                            ),
                            volume = old.volume,
                            muted = old.muted,
                            soloed = old.soloed,
                            colorIndex = index % COLOR_COUNT,
                        )
                    }.toMutableList()
                    project.sanitize()
                    return project
                }
            return RecorderProject()
        }
    }
}

/**
 * Desfazer e refazer por snapshots do projeto inteiro — port do
 * `RecorderHistory`. O modelo guarda só metadados de clipe (nunca áudio),
 * então um snapshot custa poucas centenas de bytes; guardar o JSON pronto é
 * o que devolve a semântica de valor que o struct do Swift dava de graça —
 * o chamador pode seguir mutando o objeto dele sem corromper a história.
 */
class RecorderHistory(private val limit: Int = 40) {
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Chame ANTES de mutar, com o estado como está agora. */
    fun record(project: RecorderProject) {
        past.addLast(project.serialized())
        if (past.size > limit) past.removeFirst()
        future.clear()
    }

    fun undo(current: RecorderProject): RecorderProject? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current.serialized())
        return RecorderProject.load(previous)
    }

    fun redo(current: RecorderProject): RecorderProject? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current.serialized())
        return RecorderProject.load(next)
    }

    fun clear() {
        past.clear()
        future.clear()
    }
}
