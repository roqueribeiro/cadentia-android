package com.levelhard.cadentia.features.cordas

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.levelhard.cadentia.kit.ChordLibrary
import com.levelhard.cadentia.kit.MusicNotes
import com.levelhard.cadentia.kit.cordas.AirGuitarGeometry
import com.levelhard.cadentia.kit.cordas.AirGuitarModel
import com.levelhard.cadentia.kit.cordas.ChordTranspose
import com.levelhard.cadentia.kit.cordas.CordaChords
import com.levelhard.cadentia.kit.cordas.CordaInstrument
import com.levelhard.cadentia.kit.cordas.FixedStringsStrummer
import com.levelhard.cadentia.kit.cordas.FretboardLayout
import com.levelhard.cadentia.kit.cordas.HandChirality
import com.levelhard.cadentia.kit.cordas.HandChordAssignment
import com.levelhard.cadentia.kit.cordas.HandChordMapping
import com.levelhard.cadentia.kit.cordas.HandCountConfirmer
import com.levelhard.cadentia.kit.cordas.HandFeatures
import com.levelhard.cadentia.kit.cordas.HandJoint
import com.levelhard.cadentia.kit.cordas.HandLandmarks
import com.levelhard.cadentia.kit.cordas.NailCapture
import com.levelhard.cadentia.kit.cordas.Size
import com.levelhard.cadentia.kit.cordas.TwoHandChords
import kotlin.math.abs
import kotlinx.serialization.json.Json

/**
 * O que o Cordas lembra entre uma abertura e outra — port do
 * `CordasPreferences`, `HandChordStore` e `TwoHandChordStore` (1.16).
 *
 * `SharedPreferences` direto, e não `AppSettings`: são interruptores de modo e
 * escolhas de gesto, não configuração de som. Não entram em preset nenhum e não
 * viajam para lugar nenhum.
 */
class CordasPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cadentia.cordas", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var autoPluck: Boolean
        get() = prefs.getBoolean(AUTO_PLUCK, false)
        set(value) = prefs.edit().putBoolean(AUTO_PLUCK, value).apply()

    fun loadHandChords(): Map<String, HandChordAssignment> = load(HAND_CHORDS)
    fun saveHandChords(map: Map<String, HandChordAssignment>) = save(HAND_CHORDS, map)
    fun loadTwoHandChords(): Map<String, TwoHandChords> = load(TWO_HAND_CHORDS)
    fun saveTwoHandChords(map: Map<String, TwoHandChords>) = save(TWO_HAND_CHORDS, map)

    /** O treinador de cada modo já foi lido? Um fato desta instalação, não uma preferência. */
    fun coachSeen(mode: CordasModel.Mode): Boolean = prefs.getBoolean("coach.${mode.id}", false)
    fun markCoach(mode: CordasModel.Mode) = prefs.edit().putBoolean("coach.${mode.id}", true).apply()

    private inline fun <reified T> load(key: String): Map<String, T> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, T>>(raw) }.getOrDefault(emptyMap())
    }

    private inline fun <reified T> save(key: String, map: Map<String, T>) {
        prefs.edit().putString(key, json.encodeToString(map)).apply()
    }

    companion object {
        private const val AUTO_PLUCK = "cadentia.cordas.autoPluck.v1"
        private const val HAND_CHORDS = "cadentia.cordas.handChords.v1"
        private const val TWO_HAND_CHORDS = "cadentia.cordas.twoHandChords.v1"
    }
}

/**
 * O estado do instrumento — port do `CordasModel.swift` (1.16): que cordas
 * estão presas em que casa, que acorde está em cima e qual das três formas de
 * tocar está na tela.
 *
 * Tudo o que decide alguma coisa mora no Kit. Esta classe só guarda o que a
 * tela mostra e passa trabalho para o motor. O estado observável é Compose
 * (`mutableStateOf`), o papel do `@Observable` do iOS.
 */
class CordasModel(context: Context) {
    enum class Mode(val id: String) {
        /** O telefone de pé É o braço. */
        Frets("frets"),
        /** O braço vira pads: uma mão escolhe o acorde, a outra bate. */
        Chords("chords"),
        /** O violão aparece entre as mãos. */
        Camera("camera");

        companion object {
            fun named(id: String?): Mode? = entries.firstOrNull { it.id == id }
        }
    }

    val preferences = CordasPreferences(context)
    val engine = CordaEngine()
    val geometry = AirGuitarGeometry()
    val nail = NailCapture()

    /** A batida nas cordas paradas (ver `FixedStringsStrummer`). */
    val strummer = FixedStringsStrummer()

    private var instrumentState = mutableStateOf(CordaInstrument.violao)
    var instrument: CordaInstrument
        get() = instrumentState.value
        set(value) {
            val old = instrumentState.value
            if (old.id == value.id) return
            instrumentState.value = value
            engine.instrument = value
            // O capo e a posição SOBREVIVEM à troca. Zerá-los era silencioso e
            // a forma mais rápida de fazer alguém acreditar que o app se
            // reiniciou no meio da música. Só o acorde pode não sobreviver, e
            // só porque a viola em cebolão não fala as mesmas formas.
            shift = minOf(shift, maxOf(0, value.frets - visibleFrets))
            if (mode == Mode.Chords && !value.playsChords) mode = Mode.Frets
            val wanted = chordId
            resetStrings()
            if (wanted != null) setChord(wanted)
        }

    private val modeState = mutableStateOf(Mode.Frets)
    var mode: Mode
        get() = modeState.value
        set(value) {
            // O baixo não toca acorde: se o modo sobrou de outro instrumento,
            // cai nas casas em vez de abrir uma tela que não faz sentido.
            modeState.value = if (value == Mode.Chords && !instrument.playsChords) Mode.Frets else value
        }

    /** Os modos que ESTE instrumento oferece. */
    val availableModes: List<Mode>
        get() = if (instrument.playsChords) Mode.entries else Mode.entries.filter { it != Mode.Chords }

    private val capoState = mutableIntStateOf(0)

    /** Mudar o capo reaperta o que está preso: o painel movia o capo e o instrumento seguia no tom velho. */
    var capo: Int
        get() = capoState.intValue
        set(value) {
            if (capoState.intValue == value) return
            capoState.intValue = value
            val current = chordId
            if (current != null && setChord(current)) return
            resetStrings()
        }

    var shift by mutableIntStateOf(0)

    private var visibleFretsStrummed by mutableIntStateOf(5)

    /** Nove porque é o que cabe bem no braço inteiro, e cobre duas oitavas sem tirar a mão do lugar. */
    private var visibleFretsFree by mutableIntStateOf(9)

    /**
     * Quantas casas o braço mostra — UMA POR MODO. Com a mão direita batendo o
     * braço tem 56% da tela e cinco casas já ficam grandes; sem ela, o braço
     * ocupa a tela toda e cinco casas viram degraus de cem pontos.
     */
    var visibleFrets: Int
        get() = if (handsFreeNeck) visibleFretsFree else visibleFretsStrummed
        set(value) {
            if (handsFreeNeck) visibleFretsFree = value else visibleFretsStrummed = value
        }

    /**
     * O teto de casas muda com o espaço que existe: as casas encolhem subindo o
     * braço, e num telefone pequeno cabem sete onde num grande cabem doze.
     */
    val maxVisibleFrets: Int
        get() {
            if (!handsFreeNeck) return 8
            if (lastNeckHeight <= 0) return 9
            return FretboardLayout.fretBudget(lastNeckHeight)
        }

    private var lastNeckHeight by mutableStateOf(0.0)

    /** A tela conta a altura que o braço ficou, para o painel oferecer o teto certo. */
    fun noteNeckHeight(height: Double) {
        if (abs(height - lastNeckHeight) <= 0.5) return
        lastNeckHeight = height
        visibleFrets = minOf(visibleFrets, maxVisibleFrets)
    }

    /** O braço sem faixa de batida: só nas Casas, e só com a mão direita desligada. */
    val handsFreeNeck: Boolean get() = autoPluck && mode == Mode.Frets

    var spread by mutableStateOf(1.0)
    var pixelsPerMillimetre by mutableStateOf(5.46)
    var nailEnabled by mutableStateOf(true)
    var bendEnabled by mutableStateOf(false)

    private val volumeState = mutableStateOf(0.9)
    var volume: Double
        get() = volumeState.value
        set(value) { volumeState.value = value; engine.volume = value }

    private val sustainState = mutableStateOf(1.0)
    var sustain: Double
        get() = sustainState.value
        set(value) { sustainState.value = value; engine.sustain = value }

    private val ambienceState = mutableStateOf(0.16)
    var ambience: Double
        get() = ambienceState.value
        set(value) { ambienceState.value = value; engine.ambience = value }

    private val driveState = mutableStateOf(0.35)
    var driveAmount: Double
        get() = driveState.value
        set(value) { driveState.value = value; engine.driveAmount = value }

    private val autoPluckState = mutableStateOf(preferences.autoPluck)

    /**
     * Tocar sem a mão direita: encostou na casa, a nota sai. Foi o pedido do
     * founder, com estas palavras: "só tocar a casa do braço e o som já sair".
     * Vale nas Casas e na Câmera, e não nos Acordes. Persistido: um interruptor
     * de modo que volta ao normal a cada abertura é um interruptor que ninguém
     * usa duas vezes.
     */
    var autoPluck: Boolean
        get() = autoPluckState.value
        set(value) {
            if (autoPluckState.value == value) return
            autoPluckState.value = value
            preferences.autoPluck = value
            // A janela do braço muda de tamanho junto: sem reancorar, a posição
            // guardada pode apontar para além da última casa.
            shift = minOf(shift, maxOf(0, instrument.frets - visibleFrets))
        }

    /** O acorde de cada gesto da mão esquerda, por instrumento. */
    var handChords: Map<String, HandChordAssignment> by mutableStateOf(preferences.loadHandChords())
        private set

    /** O acorde contando os dedos das DUAS mãos, por instrumento. */
    var twoHandChords: Map<String, TwoHandChords> by mutableStateOf(preferences.loadTwoHandChords())
        private set

    private val handCounts = HandCountConfirmer()

    /** A grade que vale agora para este instrumento: a escolhida, ou o padrão. */
    val twoHandGrid: TwoHandChords
        get() = twoHandChords[instrument.id] ?: TwoHandChords.standard(CordaChords.set(instrument))

    fun setTwoHandChord(chord: String, left: Int, right: Int) {
        val grid = twoHandGrid.copy(chords = twoHandGrid.chords.toList())
        grid.set(chord, left, right)
        twoHandChords = twoHandChords + (instrument.id to grid)
        preferences.saveTwoHandChords(twoHandChords)
    }

    fun resetTwoHandChords() {
        twoHandChords = twoHandChords - instrument.id
        preferences.saveTwoHandChords(twoHandChords)
    }

    /** Contar os dedos vale, em vez do desenho da mão, quando a direita não faz o ritmo. */
    val countsChords: Boolean get() = autoPluck

    fun setHandChord(chord: String, shapeIndex: Int) {
        val assignment = (handChords[instrument.id] ?: HandChordAssignment()).let { it.copy(chords = it.chords.toList()) }
        assignment.set(chord, shapeIndex)
        handChords = handChords + (instrument.id to assignment)
        preferences.saveHandChords(handChords)
        geometry.chordNames = chordNames
    }

    fun resetHandChords() {
        handChords = handChords - instrument.id
        preferences.saveHandChords(handChords)
        geometry.chordNames = chordNames
    }

    /** A casa presa em cada corda. `-1` é corda abafada. */
    var frets: List<Int> by mutableStateOf(emptyList())
        private set
    var chordId: String? by mutableStateOf(null)
        private set
    var chordIndex by mutableIntStateOf(-1)
        private set

    /** Preenchido pelo modo câmera, para a tela dizer o que está lendo. */
    var cameraFrame: AirGuitarGeometry.Frame by mutableStateOf(AirGuitarGeometry.Frame())

    /** Uma mão descansa no cavalete. A tela tem que MOSTRAR: abafamento invisível parece app travado. */
    var palmMuted by mutableStateOf(false)

    /** O que o rastreador está fazendo, preenchido pela câmera a cada quadro. */
    var trackerInfo by mutableStateOf("")

    init {
        resetStrings()
        engine.instrument = instrument
    }

    // ── cordas ───────────────────────────────────────────────────────────

    fun resetStrings() {
        frets = List(instrument.stringCount) { capo }
        chordId = null
        chordIndex = -1
        nail.reset(instrument.stringCount)
    }

    fun midi(string: Int): Int? {
        if (string < 0 || string >= frets.size || string >= instrument.stringCount) return null
        val fret = frets[string]
        if (fret < 0) return null
        return instrument.strings[string].midi + maxOf(fret, capo)
    }

    fun setFret(fret: Int, string: Int) {
        if (string < 0 || string >= frets.size) return
        if (frets[string] == fret) return
        frets = frets.toMutableList().also { it[string] = fret }
    }

    /**
     * Os acordes que os gestos tocam: o conjunto do instrumento, ou o que a
     * pessoa escolheu por cima dele. Uma lista só, para o mapeamento de gesto,
     * a legenda da tela e a tela de escolha não discordarem entre si.
     */
    val chordNames: List<String>
        get() {
            val fallback = CordaChords.set(instrument)
            val assignment = handChords[instrument.id] ?: return fallback
            return assignment.resolved(fallback)
        }

    /** Todo acorde que ESTE instrumento sabe tocar: nome sem forma deixaria o gesto mudo. */
    val playableChordNames: List<String>
        get() {
            if (instrument.id == "viola") return CordaChords.violaSet
            return ChordLibrary.all.filter { CordaChords.frets(it.id, instrument) != null }.map { it.id }
        }

    /** Em que as cordas estão afinadas agora, por extenso — o leitor de tela lê isto. */
    val notesSummary: String
        get() = (0 until instrument.stringCount).mapNotNull { index ->
            midi(index)?.let { MusicNotes.noteNames[((it % 12) + 12) % 12] }
        }.joinToString(" ")

    fun setChord(name: String): Boolean {
        val shape = CordaChords.frets(name, instrument, capo) ?: return false
        frets = shape
        chordId = name
        chordIndex = chordNames.indexOf(name)
        return true
    }

    /** Tocar no acorde que já está escolhido DESLIGA ele, como qualquer coisa selecionável. */
    fun setChord(index: Int) {
        val names = chordNames
        if (index < 0 || index >= names.size) return
        if (chordIndex == index) {
            clearChord()
            return
        }
        setChord(names[index])
    }

    /** Volta para as cordas soltas: nenhuma forma, nenhuma corda emudecida. */
    fun clearChord() {
        resetStrings()
        haptics?.chord()
    }

    /** Quem dá os toques de resposta. A tela liga; sem tela (testes), ninguém. */
    var haptics: CordasHaptics? = null

    // ── tocar ────────────────────────────────────────────────────────────

    fun pluck(string: Int, velocity: Double, delay: Double = 0.0, nail: Double = 0.5, muted: Boolean = false) {
        val note = midi(string) ?: return
        engine.pluck(string, note, velocity, delay, nail, muted)
    }

    /** Uma passada por todas as cordas, espalhada como uma batida de verdade. */
    fun strumAll(velocity: Double = 0.7, down: Boolean = true) {
        val count = instrument.stringCount
        for (step in 0 until count) {
            val index = if (down) step else count - 1 - step
            pluck(index, velocity, delay = step * 0.028, nail = 0.55)
        }
    }

    fun play(plucks: List<NailCapture.Pluck>) {
        for (pluck in plucks) pluck(pluck.string, pluck.velocity, pluck.delay, pluck.nail, pluck.muted)
    }

    /** A batida das cordas paradas — outro tipo, de propósito: sai de uma faixa fixa, não de um violão desenhado. */
    fun playFixed(plucks: List<FixedStringsStrummer.Pluck>) {
        for (pluck in plucks) pluck(pluck.string, pluck.velocity, pluck.delay, nail = 0.6)
    }

    fun playAir(plucks: List<AirGuitarGeometry.Pluck>) {
        for (pluck in plucks) pluck(pluck.string, pluck.velocity, pluck.delay, nail = 0.6)
    }

    // ── a janela do braço ────────────────────────────────────────────────

    fun layout(size: Size): FretboardLayout = FretboardLayout(
        size = size, instrument = instrument, visibleFrets = visibleFrets,
        shift = shift, pixelsPerMillimetre = pixelsPerMillimetre, spreadFactor = spread,
        hasRail = mode != Mode.Chords,
        padCount = if (mode == Mode.Chords) chordNames.size else 0,
        handsFree = handsFreeNeck,
    )

    /**
     * Uma corda desliza; dois ou mais dedos levam a forma inteira junto, e as
     * notas que já soam dobram para o tom novo em vez de tocar de novo.
     */
    fun transposeShape(delta: Int): Int {
        val working = frets.toMutableList()
        val moved = ChordTranspose.transpose(working, delta, instrument.frets)
        if (moved == 0) return 0
        frets = working
        for (index in frets.indices) applyPitch(index)
        followShape()
        return moved
    }

    fun goToPosition(position: Int) {
        val working = frets.toMutableList()
        shift = ChordTranspose.jump(position, working, visibleFrets, instrument.frets)
        frets = working
        for (index in frets.indices) applyPitch(index)
        engine.warmup(shift, visibleFrets)
    }

    fun followShape() {
        val next = ChordTranspose.windowFollowing(frets, shift, visibleFrets, instrument.frets)
        if (next != shift) {
            shift = next
            engine.warmup(shift, visibleFrets)
        }
    }

    /**
     * A nota que soa é a casa em que foi tocada mais o quanto a forma andou
     * desde então: é o que faz arrastar um acorde soar como glissando.
     */
    fun applyPitch(index: Int) {
        if (index >= frets.size) return
        val fret = frets[index]
        if (fret < 0) {
            engine.damp(index, hard = true)
            return
        }
        val target = midi(index) ?: return
        val open = instrument.strings[index].midi
        engine.bend(index, (target - open).toDouble() - maxOf(0, capo))
    }

    // ── a câmera ─────────────────────────────────────────────────────────

    private var lastTelemetry = 0.0

    /**
     * Quatro vezes por segundo, o que o modo câmera acredita — no logcat, que
     * sobrevive a fechar e reabrir o app e se lê do Mac enquanto alguém toca.
     */
    private fun telemetry(hands: List<HandLandmarks>, frame: AirGuitarGeometry.Frame, viewSize: Size, time: Double) {
        if (time - lastTelemetry <= 0.25) return
        lastTelemetry = time
        if (!Log.isLoggable(TELEMETRY_TAG, Log.INFO)) return
        fun short(value: Double) = String.format(java.util.Locale.ROOT, "%.2f", value)
        val strings = (0 until instrument.stringCount).map { geometry.stringU(it) }
        val names = hands.joinToString("") {
            when (it.chirality) {
                HandChirality.Left -> "E"
                HandChirality.Right -> "D"
                HandChirality.Unknown -> "?"
            }
        }
        val body = frame.body?.let { "(${it.x.toInt()},${it.y.toInt()})" } ?: "-"
        val drawn = AirGuitarModel(frame.posture, instrument.stringCount)
        val tail = geometry.point(drawn.tail, 0.0)
        val head = geometry.point(drawn.head, 0.0)
        val wrists = hands.joinToString("") { "(${it[HandJoint.Wrist].x.toInt()},${it[HandJoint.Wrist].y.toInt()})" }
        val pick = frame.pickU?.let(::short) ?: "-"
        val line = "CORDAS cal=${frame.calibration.id} hands=${hands.size}$names " +
            "mao=${frame.handedness.id} body=$body len=${frame.length.toInt()} " +
            "eixo=(${short(frame.axis.dx)},${short(frame.axis.dy)}) " +
            "postura=${frame.posture.id} cordas=[${short(strings.firstOrNull() ?: 0.0)}…" +
            "${short(strings.lastOrNull() ?: 0.0)}] palheta_u=$pick " +
            "modo=${frame.mode.id} mascara=${frame.rawMask} plucks=${frame.plucks.size} " +
            "borda=${frame.pickHandNearEdge} tela=${viewSize.width.toInt()}x${viewSize.height.toInt()} " +
            "rabo=(${tail.x.toInt()},${tail.y.toInt()}) cabeca=(${head.x.toInt()},${head.y.toInt()}) " +
            "pulsos=$wrists $trackerInfo"
        Log.i(TELEMETRY_TAG, line)
    }

    fun handleCamera(hands: List<HandLandmarks>, viewSize: Size, time: Double) {
        geometry.chordNames = chordNames
        val frame = geometry.update(hands, time, instrument, viewSize) { intent ->
            when (intent) {
                is AirGuitarGeometry.ChordIntent.Chord -> {
                    // Com as duas mãos contando, quem manda é a contagem.
                    if (!countsChords) {
                        setChord(intent.name)
                        haptics?.chord()
                        // Sem mão direita: o gesto da esquerda já faz o acorde soar.
                        if (autoPluck) strumAll(velocity = 0.62)
                    }
                }
                // A pinça NÃO emudece mais as cordas: com as cordas fixas a
                // câmera é acompanhamento. Quem quer solar usa as Casas.
                is AirGuitarGeometry.ChordIntent.Solo, AirGuitarGeometry.ChordIntent.ReleaseSolo,
                AirGuitarGeometry.ChordIntent.Unchanged -> Unit
            }
            frets.map { it >= 0 }
        }
        cameraFrame = frame
        telemetry(hands, frame, viewSize, time)

        // AS DUAS MÃOS CONTANDO, quando a direita está livre do ritmo: 25
        // combinações contra as 9 formas de uma só.
        if (countsChords) {
            val left = frame.neckHand?.let { HandChordMapping.fingerCount(HandFeatures.shapeMask(it)) }
            val right = frame.pickHand?.let { HandChordMapping.fingerCount(HandFeatures.shapeMask(it)) }
            val pair = handCounts.update(left, right, time)
            if (pair != null) {
                val name = twoHandGrid.chord(pair.first, pair.second)
                if (name != null && setChord(name)) {
                    haptics?.chord()
                    strumAll(velocity = 0.62)
                }
            }
        }

        // A BATIDA vem das cordas paradas, e não do violão desenhado: a escala
        // vinda do rastreamento mudava as cordas de lugar debaixo da mão.
        val handY = frame.pickHand?.let { HandFeatures.palm(it).y }
        val strummed = strummer.update(handY, viewSize.height, time, instrument.stringCount)
        playFixed(strummed)
        // No ar não há nada para sentir: o toque no pulso é a única confirmação de que a passada pegou.
        if (strummed.isNotEmpty()) haptics?.strum(strummed.size)
        frame.tchacVelocity?.let {
            engine.tchac(it)
            haptics?.tchac()
        }
        if (frame.dampAll) {
            for (index in frets.indices) {
                if (index < engine.amplitude.size && engine.amplitude[index] > 0.12) engine.damp(index)
            }
        }
    }

    companion object {
        /** `adb shell setprop log.tag.CadentiaCordas INFO` liga a telemetria da câmera. */
        const val TELEMETRY_TAG = "CadentiaCordas"
    }
}

/** Os toques que o instrumento devolve — port do `CordasHaptics`. */
interface CordasHaptics {
    fun strum(strings: Int)
    fun chord()
    fun tchac()
    fun light()
    fun medium()
}
