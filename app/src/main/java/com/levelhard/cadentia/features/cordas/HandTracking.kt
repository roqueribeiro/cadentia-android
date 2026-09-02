package com.levelhard.cadentia.features.cordas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.levelhard.cadentia.kit.cordas.CameraFrameMapping
import com.levelhard.cadentia.kit.cordas.HandChirality
import com.levelhard.cadentia.kit.cordas.HandLandmarks
import com.levelhard.cadentia.kit.cordas.HandSmoother
import com.levelhard.cadentia.kit.cordas.Point
import com.levelhard.cadentia.kit.cordas.Size
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * De onde as mãos vêm — port do `HandTracking.swift` (1.16).
 *
 * Duas implementações, e a segunda não é luxo: [ReplayHands] alimenta uma pose
 * gravada em vez de uma câmera, que é o que deixa o modo câmera ser exercitado
 * num emulador sem permissão e sem mão nenhuma na frente da máquina. Sem isso
 * a única prova deste modo seria um print, e render não é teste.
 *
 * O estado observável (status, fps, mãos cruas) é Compose, porque a tela lê.
 */
interface HandTrackingSource {
    var onFrame: ((List<HandLandmarks>, Size, Double) -> Unit)?
    val status: HandTrackingStatus

    /** O que o rastreador está conseguindo de verdade: o número a olhar antes de mexer em qualquer constante. */
    val framesPerSecond: Double

    /** Para que lado as coordenadas da câmera vão. Ajustável porque a resposta mora no aparelho. */
    var mapping: CameraFrameMapping

    /** Quantas mãos o detector viu ANTES do filtro: a diferença para o que a geometria recebe é toda a pergunta. */
    val rawHandCount: Int
    val orientationName: String

    /** A prévia, quando há câmera; a tela põe na hierarquia. `null` no replay. */
    val previewView: PreviewView?

    fun start(viewSize: Size)
    fun stop()
    fun updateViewSize(size: Size)
    fun flipCamera()
}

sealed class HandTrackingStatus {
    object Idle : HandTrackingStatus()
    object Starting : HandTrackingStatus()
    object Running : HandTrackingStatus()

    /** A pessoa disse não. O braço e os acordes seguem valendo, e a tela diz isso em vez de mostrar uma câmera morta. */
    object Denied : HandTrackingStatus()
    data class Failed(val reason: String) : HandTrackingStatus()
}

/** A permissão da câmera, num lugar só. */
object CameraPermission {
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

/**
 * A resposta que tem que sobreviver a um relançamento: para que lado a câmera
 * acabou sendo, ninguém deve descobrir duas vezes.
 */
class CameraMappingMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cadentia.cordas", Context.MODE_PRIVATE)

    fun load(): CameraFrameMapping {
        val raw = prefs.getString(KEY, null) ?: return CameraFrameMapping.frontCameraDefault
        return runCatching { Json.decodeFromString<CameraFrameMapping>(raw) }.getOrDefault(CameraFrameMapping.frontCameraDefault)
    }

    fun save(mapping: CameraFrameMapping) {
        prefs.edit().putString(KEY, Json.encodeToString(mapping)).apply()
    }

    private companion object {
        const val KEY = "cordas.camera.mapping"
    }
}

/**
 * MediaPipe Hand Landmarker sobre o CameraX, no aparelho.
 *
 * O iOS usa o Vision: as mesmas 21 juntas, zero bytes no bundle. O Android não
 * tem nada do sistema, então o modelo vem nos assets e a inferência roda no
 * `tasks-vision` (Apache-2.0; ver o crédito na tela Sobre e a nota sobre a
 * telemetria no manifesto).
 *
 * Duas conversões moram aqui e em nenhum outro lugar, porque errar qualquer
 * uma não dá erro nenhum:
 *
 * - **A rotação.** O quadro do `ImageAnalysis` chega no referencial do sensor;
 *   `rotationDegrees` diz quanto girar para ficar de pé na tela. O bitmap é
 *   girado ANTES de ir ao MediaPipe, então os landmarks já saem no referencial
 *   da tela e o tamanho da imagem que vale é o do quadro girado.
 * - **O espelho e a mão.** A prévia da câmera frontal é espelhada (é como a
 *   pessoa espera se ver); o quadro analisado NÃO é. O [CameraFrameMapping]
 *   vira o X para o ponto cair onde a mão aparece. E o MediaPipe rotula a mão
 *   ASSUMINDO imagem espelhada, então num quadro não espelhado "Left" é a mão
 *   direita da pessoa: a etiqueta é trocada aqui. Nada quebra se isto estiver
 *   errado — o app só decide que um destro é canhoto. Os botões Espelho e Mão
 *   são a saída para o que sobrar.
 */
class MediaPipeHandTracker(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onPermissionNeeded: () -> Unit,
) : HandTrackingSource {
    override var onFrame: ((List<HandLandmarks>, Size, Double) -> Unit)? = null

    override var status: HandTrackingStatus by mutableStateOf(HandTrackingStatus.Idle)
        private set
    override var framesPerSecond: Double by mutableDoubleStateOf(0.0)
        private set
    override var rawHandCount: Int by mutableIntStateOf(0)
        private set
    override val orientationName: String get() = if (facingFront) "front" else "back"

    private val memory = CameraMappingMemory(context)
    override var mapping: CameraFrameMapping = memory.load()
        set(value) {
            field = value
            memory.save(value)
        }

    override var previewView: PreviewView? by mutableStateOf(null)
        private set

    private var facingFront = true
    private var viewSize = Size.zero
    private var provider: ProcessCameraProvider? = null
    private var landmarker: HandLandmarker? = null
    private var executor: ExecutorService? = null
    private val smoothers = HashMap<HandChirality, HandSmoother>().apply {
        for (c in HandChirality.entries) put(c, HandSmoother())
    }
    private var lastTime = 0.0
    private var fps = 0.0
    private var imageSize = Size.zero

    fun onPermissionResult(granted: Boolean) {
        if (granted) configure() else status = HandTrackingStatus.Denied
    }

    override fun start(viewSize: Size) {
        this.viewSize = viewSize
        if (status == HandTrackingStatus.Running || status == HandTrackingStatus.Starting) return
        status = HandTrackingStatus.Starting
        if (CameraPermission.granted(context)) {
            configure()
        } else {
            // A permissão flui INLINE, a regra da casa: o modo abre e pede enquanto abre.
            onPermissionNeeded()
        }
    }

    private fun configure() {
        val executor = Executors.newSingleThreadExecutor()
        this.executor = executor
        try {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> deliver(result) }
                .setErrorListener { error -> Log.w(TAG, "hand landmarker: ${error.message}") }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, options)
        } catch (error: Exception) {
            Log.w(TAG, "modelo indisponível", error)
            status = HandTrackingStatus.Failed("model")
            return
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = try {
                future.get()
            } catch (error: Exception) {
                status = HandTrackingStatus.Failed("provider")
                return@addListener
            }
            this.provider = provider
            bind(provider)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(provider: ProcessCameraProvider) {
        val executor = executor ?: return
        val preview = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        // Prévia e análise com a MESMA proporção: o mapeamento "cover" dos
        // pontos assume que as duas mostram o mesmo recorte do sensor.
        val ratio = AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val previewUseCase = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
        previewUseCase.setSurfaceProvider(preview.surfaceProvider)
        // 720p, não VGA: a 640×480 uma mão do outro lado da sala tem duzentos
        // pixels e cada ponta de dedo tremula um pixel, e esse tremor É o
        // acorde piscando numa mão parada.
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(executor) { image -> analyze(image) }
        val selector = if (facingFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, analysis)
        } catch (error: Exception) {
            Log.w(TAG, "câmera indisponível", error)
            status = HandTrackingStatus.Failed("camera")
            return
        }
        previewView = preview
        status = HandTrackingStatus.Running
    }

    /** Na thread da análise: gira o quadro e manda ao modelo. */
    private fun analyze(image: ImageProxy) {
        val landmarker = landmarker
        if (landmarker == null) {
            image.close()
            return
        }
        try {
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            imageSize = Size(upright.width.toDouble(), upright.height.toDouble())
            // O instante da CAPTURA, não o de quando este código chegou nele: a
            // batida interpola o instante de cada corda dentro do intervalo
            // entre dois quadros, e tremor no carimbo cai direto no tempo da nota.
            val stampMs = image.imageInfo.timestamp / 1_000_000
            landmarker.detectAsync(BitmapImageBuilder(upright).build(), stampMs)
        } catch (error: Exception) {
            Log.w(TAG, "quadro descartado: ${error.message}")
        } finally {
            image.close()
        }
    }

    /** Na thread do MediaPipe: mapeia, alisa e entrega na principal. */
    private fun deliver(result: HandLandmarkerResult) {
        val now = result.timestampMs() / 1000.0
        if (lastTime > 0) fps += (1 / maxOf(now - lastTime, 0.001) - fps) * 0.15
        lastTime = now
        val view = viewSize
        val image = imageSize
        val mapping = mapping
        val landmarks = result.landmarks()
        val handedness = result.handedness()
        val hands = ArrayList<HandLandmarks>()
        val used = HashSet<HandChirality>()
        if (view.width > 0 && view.height > 0 && image.width > 0 && image.height > 0) {
            for ((index, joints) in landmarks.withIndex()) {
                if (index >= 2 || joints.size != 21) continue
                var chirality = when (handedness.getOrNull(index)?.firstOrNull()?.categoryName()) {
                    // A etiqueta do MediaPipe assume imagem espelhada; o quadro daqui não é.
                    "Left" -> HandChirality.Right
                    "Right" -> HandChirality.Left
                    else -> HandChirality.Unknown
                }
                // Duas mãos reclamando o mesmo lado é o detector em dúvida: a
                // segunda pega a vaga livre em vez de dividir um alisador.
                if (chirality in used) chirality = if (chirality.opposite in used) HandChirality.Unknown else chirality.opposite
                used += chirality
                val points = joints.map { mapping.viewPoint(Point(it.x().toDouble(), it.y().toDouble()), image, view) }
                val smoother = smoothers.getValue(chirality)
                smoother.smooth(points, now, chirality)?.let { hands += it }
            }
        }
        if (hands.isEmpty()) for (smoother in smoothers.values) smoother.reset()
        val count = landmarks.size
        val rate = fps
        ContextCompat.getMainExecutor(context).execute {
            rawHandCount = count
            framesPerSecond = rate
            onFrame?.invoke(hands, view, now)
        }
    }

    override fun stop() {
        provider?.unbindAll()
        provider = null
        previewView = null
        landmarker?.close()
        landmarker = null
        executor?.shutdown()
        executor = null
        lastTime = 0.0
        fps = 0.0
        framesPerSecond = 0.0
        rawHandCount = 0
        status = HandTrackingStatus.Idle
    }

    override fun updateViewSize(size: Size) {
        viewSize = size
    }

    override fun flipCamera() {
        facingFront = !facingFront
        stop()
        start(viewSize)
    }

    private companion object {
        const val TAG = "CadentiaCordas"
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}

/**
 * Toca uma pose gravada em vez de abrir a câmera. É o que torna o modo câmera
 * testável: `qa-cordas-replay` no emulador, sem permissão, sem mão, e um
 * desenho determinístico para olhar.
 */
class ReplayHands(private var frames: List<List<HandLandmarks>> = emptyList()) : HandTrackingSource {
    override var onFrame: ((List<HandLandmarks>, Size, Double) -> Unit)? = null
    override var status: HandTrackingStatus by mutableStateOf(HandTrackingStatus.Idle)
        private set
    override val framesPerSecond: Double get() = if (status == HandTrackingStatus.Running) 30.0 else 0.0
    override val rawHandCount: Int get() = if (status == HandTrackingStatus.Running) 2 else 0
    override val orientationName: String get() = "replay"

    /** O replay já fala em coordenadas da view. */
    override var mapping: CameraFrameMapping = CameraFrameMapping.identity
    override val previewView: PreviewView? get() = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var viewSize = Size.zero
    private var index = 0
    private var elapsed = 0.0

    override fun start(viewSize: Size) {
        this.viewSize = viewSize
        // Sem traço para tocar, segura a pose sintética: é o que faz o modo
        // câmera desenhar numa máquina sem câmera.
        if (frames.isEmpty()) frames = listOf(stillPose(viewSize))
        status = HandTrackingStatus.Running
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(33)
                if (frames.isEmpty()) continue
                elapsed += 1.0 / 30
                onFrame?.invoke(frames[index % frames.size], this@ReplayHands.viewSize, elapsed)
                index += 1
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        status = HandTrackingStatus.Idle
    }

    override fun updateViewSize(size: Size) {
        viewSize = size
    }

    override fun flipCamera() = Unit

    companion object {
        /** Uma pose sintética: as duas mãos paradas, um braço de distância. */
        fun stillPose(viewSize: Size): List<HandLandmarks> {
            fun hand(centre: Point, spread: Double, chirality: HandChirality): HandLandmarks {
                val points = (0 until 21).map { slot ->
                    val finger = maxOf(0, (slot - 1) / 4)
                    val along = ((slot - 1) % 4 + 1) * spread * 0.32
                    val across = (finger - 1.5) * spread * 0.30
                    if (slot == 0) centre else Point(centre.x + across, centre.y - along)
                }
                return HandLandmarks(points, chirality)
            }
            // Os mesmos lugares que o guia pede, para o replay desenhar o que uma pose real desenha.
            val neck = Point(viewSize.width * 0.34, viewSize.height * 0.26)
            val pick = Point(viewSize.width * 0.58, viewSize.height * 0.62)
            return listOf(hand(neck, 46.0, HandChirality.Left), hand(pick, 46.0, HandChirality.Right))
        }
    }
}

/** Relógio monotônico em segundos, para quem precisa de "agora" fora de um quadro. */
internal fun nowSeconds(): Double = SystemClock.elapsedRealtimeNanos() / 1e9
