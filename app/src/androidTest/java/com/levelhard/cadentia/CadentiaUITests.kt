package com.levelhard.cadentia

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

/**
 * Espelho dos 29 `CadentiaUITests` do iOS, com os mesmos identificadores
 * (`testTag` = `accessibilityIdentifier`, expostos como resource-id pelo
 * `testTagsAsResourceId`) e os mesmos ganchos de lançamento (extras do
 * intent = launch arguments).
 *
 * Em cima do **uiautomator**, não da regra de teste do Compose: as telas
 * com laço de redesenho (onda dos stems, braço do Cordas, osciloscópio) nunca
 * ficam "idle" para o Compose e a regra estourava em `ComposeNotIdleException`
 * (emulador, 05/09). O uiautomator lê a árvore de acessibilidade como o
 * XCUITest lê a do iOS, e não espera ninguém.
 *
 * Rodam contra a build debug no emulador:
 * `assembleDebug assembleDebugAndroidTest` → `adb install` dos dois →
 * `adb shell am instrument -w com.levelhard.cadentia.debug.test/androidx.test.runner.AndroidJUnitRunner`.
 *
 * O que o emulador não prova (som que sai, câmera com mãos) fica de fora,
 * como no simulador do iOS: aqui o portão é "a tela responde e o processo
 * continua vivo".
 */
@RunWith(AndroidJUnit4::class)
class CadentiaUITests {
    private lateinit var device: UiDevice
    private var scenario: ActivityScenario<MainActivity>? = null

    /** Nome do teste em curso, para o print da falha. */
    private var currentTestName = "teste"

    @get:Rule
    val currentTest: TestWatcher = object : TestWatcher() {
        override fun starting(description: Description) {
            currentTestName = description.methodName
        }
    }

    /**
     * Um print ANTES de falhar, em /sdcard/Download/cadentia-ui/<teste>.png (o
     * `attach` do XCTest). No `TestWatcher.failed` já era tarde: o `@After`
     * fecha a Activity e o print saía branco.
     */
    private fun fail(message: String): Nothing {
        runCatching {
            val dir = File("/sdcard/Download/cadentia-ui").apply { mkdirs() }
            device.takeScreenshot(File(dir, "$currentTestName.png"))
            // A árvore que o teste viu, ao lado do print: quando o print mostra
            // o texto e a busca não achou, é aqui que a diferença aparece.
            device.dumpWindowHierarchy(File(dir, "$currentTestName.xml"))
        }
        throw AssertionError(message)
    }

    /** O texto de um nó com o dos filhos: um botão do Compose com ícone + rótulo expõe o rótulo num filho. */
    private fun textOf(tag: String): String? {
        val node = device.findObject(byTag(tag)) ?: return null
        val own = node.text?.takeIf { it.isNotBlank() }
        if (own != null) return own
        return node.children.mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }.joinToString(" ").ifBlank { null }
    }

    /** Espera o nó marcado dizer `expected` (texto próprio ou dos filhos). */
    private fun waitForTagText(tag: String, expected: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (textOf(tag) == expected) return true
            sleep(250)
        }
        return false
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun string(id: Int): String = context.getString(id)
    private val density: Float get() = context.resources.displayMetrics.density

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Sem esperar "idle" de acessibilidade: telas com LED, medidor ou onda
        // animando nunca ficam quietas, e o uiautomator esperava até o teto
        // (10 s) para depois devolver uma árvore velha — o botão já dizia
        // "Iniciar" na tela e a busca não via (print do drumsPadsAndPresetSheet).
        Configurator.getInstance().waitForIdleTimeout = 0
        Configurator.getInstance().waitForSelectorTimeout = 0
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    // ---- o `launch(args)` do iOS ----

    private fun launch(vararg extras: Pair<String, Any>) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("qa-no-splash", true)
            putExtra("qa-tuner-silent", true)
            for ((key, value) in extras) {
                when (value) {
                    is Boolean -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
        }
        scenario?.close()
        scenario = ActivityScenario.launch(intent)
        device.waitForIdle(2_000)
    }

    private fun byTag(tag: String) = By.res(tag)

    /**
     * `waitForExistence` do XCTest. A árvore de acessibilidade só tem o que
     * está na tela: o que não apareceu no tempo é procurado rolando o
     * primeiro contêiner rolável (o XCUITest rola sozinho; aqui é à mão).
     */
    private fun waitForTag(tag: String, timeoutMs: Long = 10_000): UiObject2 =
        waitFor(byTag(tag), "'$tag'", timeoutMs)

    private fun waitForText(text: String, timeoutMs: Long = 10_000): UiObject2 =
        waitFor(By.text(text), "texto '$text'", timeoutMs)

    private fun waitFor(selector: androidx.test.uiautomator.BySelector, label: String, timeoutMs: Long): UiObject2 {
        val direct = device.wait(Until.findObject(selector), minOf(timeoutMs, 3_000))
        if (direct != null) return direct
        val scrollable = device.findObject(By.scrollable(true))
        if (scrollable != null) {
            runCatching { scrollable.scrollUntil(Direction.DOWN, Until.hasObject(selector)) }
            device.findObject(selector)?.let { return it }
            runCatching { scrollable.scrollUntil(Direction.UP, Until.hasObject(selector)) }
            device.findObject(selector)?.let { return it }
        }
        val found = device.wait(Until.findObject(selector), maxOf(timeoutMs - 3_000, 1_000))
        return found ?: fail("$label não apareceu em ${timeoutMs / 1000} s")
    }

    private fun tagExists(tag: String): Boolean = device.hasObject(byTag(tag))

    private fun tap(tag: String, timeoutMs: Long = 10_000) {
        waitForTag(tag, timeoutMs).click()
        device.waitForIdle(1_000)
    }

    private fun sleep(ms: Long) = Thread.sleep(ms)

    /**
     * Abre o mixer e puxa a folha até o topo: recolhida (300 dp) só as faixas
     * aparecem; tom e velocidade ficam abaixo (o iOS sobe pelo detent grande).
     */
    private fun openMixerExpanded() {
        tap("stems.mixer")
        val first = waitForTag("stems.solo.drums").visibleBounds
        device.swipe(first.centerX(), first.top - 40, first.centerX(), 120, 25)
        sleep(900) // a folha assenta no detent de cima
    }

    /**
     * Abre "Adicionar músicas" no cartão do repertório expandido, busca e toca
     * na primeira. Como no iOS, a folha FICA aberta depois de adicionar (dá
     * para adicionar várias); o teste a fecha com o voltar, o que o teste do
     * iOS faz pela barra de navegação. A busca é o que torna o toque
     * determinístico: o `qa-reset` não limpa as Recentes.
     */
    private fun addToSetlist(search: String) {
        tap("setlist.addSongs")
        waitForTag("setlist.search").text = search
        sleep(300)
        tap("setlist.add.0")
        sleep(300)
        // O primeiro voltar pode só fechar o teclado que o campo de busca abriu.
        repeat(3) {
            if (!tagExists("setlist.search")) return
            device.pressBack()
            device.wait(Until.gone(byTag("setlist.search")), 2_000)
        }
        check(!tagExists("setlist.search"), "a folha de adicionar músicas não fechou")
    }

    /** `stateDescription` de um nó pelo resource-id (o uiautomator não expõe). */
    private fun stateDescription(tag: String): String? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.viewIdResourceName == tag) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        return find(root)?.stateDescription?.toString()
    }

    // ---- Afinador ----

    @Test
    fun launchShowsTunerGauge() {
        launch()
        waitForTag("tuner.gauge")
    }

    @Test
    fun tuningSheetSearchesByBandPicksAndRemembers() {
        launch("qa-reset" to true)
        tap("tuner.instrument")
        waitForTag("tuning.search").text = "drop c"
        tap("tuning.row.guitar-drop-c")
        // A corda mais grave da Drop C é um Dó.
        assertTrue(
            "a corda 0 não virou Dó",
            device.wait(Until.hasObject(byTag("tuner.string.0").textStartsWith("C")), 5_000),
        )
        // Reabrir mostra a seção de recentes com a escolha.
        tap("tuner.instrument")
        waitForTag("tuning.recents")
        waitForTag("tuning.recent.guitar-drop-c")
    }

    @Test
    fun brazilianTuningsAreInTheCatalogWithTheRightNotes() {
        launch("qa-reset" to true)
        tap("tuner.instrument")
        waitForTag("tuning.search").text = "cebol"
        waitForTag("tuning.row.viola-cebolao-re")
        waitForTag("tuning.row.viola-cebolao-mi")
    }

    @Test
    fun pinnedTuningDoesNotBlankItsRowInTheSection() {
        launch("qa-reset" to true)
        tap("tuner.instrument")
        waitForTag("tuning.search").text = "open g"
        tap("tuning.row.guitar-open-g")
        tap("tuner.instrument")
        // A afinação escolhida aparece nos recentes E continua na seção dela.
        waitForTag("tuning.recent.guitar-open-g")
        waitForTag("tuning.search").text = "open g"
        waitForTag("tuning.row.guitar-open-g")
    }

    // ---- Metrônomo ----

    @Test
    fun metronomeStartsStopsAndTapTempoChangesBPM() {
        launch("qa-tab" to "metronome", "qa-reset" to true)
        val toggle = waitForTag("metronome.toggle")
        waitForText("120")
        toggle.click()
        waitForText(string(R.string.music_metronome_stop), 3_000)
        waitForTag("metronome.toggle").click()
        waitForText(string(R.string.music_metronome_start), 3_000)

        device.wait(Until.findObject(By.desc(string(R.string.music_metronome_decrement))), 3_000)!!.click()
        waitForText("119", 3_000)
        waitForTag("metronome.tap")
        waitForText("3:2")
        waitForTag("metronome.bpmDetector.start")
        waitForTag("metronome.practiceTimer.start")
    }

    // ---- Instrumentos ----

    @Test
    fun instrumentsHubHoldsEveryInstrument() {
        launch("qa-tab" to "instruments")
        for (id in listOf("cordas", "piano", "bass", "drums")) waitForTag("instruments.$id", 15_000)
        tap("instruments.drums")
        waitForTag("drums.pads", 15_000)
    }

    @Test
    fun drumsScreenOpensWithoutFreezing() {
        launch()
        waitForTag("tuner.gauge", 15_000)
        tap("tabs.instruments")
        tap("instruments.drums")
        waitForTag("drums.pads", 15_000)
    }

    @Test
    fun drumsPadsAndPresetSheet() {
        launch("qa-tab" to "drums", "qa-reset" to true)
        waitForTag("drums.pads")
        // Sem padrão não há transporte: o botão só existe com um groove escolhido.
        assertFalse(tagExists("drums.toggle"))
        tap("drums.presets")
        tap("drums.preset.rock-basic")
        sleep(600) // a folha fecha
        val start = string(R.string.music_metronome_start)
        val stop = string(R.string.music_metronome_stop)
        waitForTag("drums.toggle", 5_000).click()
        check(waitForTagText("drums.toggle", stop, 5_000), "o botão não virou Parar (diz '${textOf("drums.toggle")}')")
        sleep(800)
        device.findObject(byTag("drums.toggle"))!!.click()
        check(waitForTagText("drums.toggle", start, 8_000), "o botão não voltou a Iniciar (diz '${textOf("drums.toggle")}')")
    }

    @Test
    fun pianoModesRender() {
        launch("qa-tab" to "piano")
        waitForTag("piano.keyboard")
        // Acordes e Escalas subiram para o hub: o seletor de modos saiu junto.
        assertFalse(tagExists("piano.mode.chords"))
    }

    @Test
    fun pianoIsPushedWithANavigationBarAndCanGoBack() {
        launch("qa-tab" to "piano")
        waitForTag("piano.keyboard", 30_000)
        tap("instruments.back")
        waitForTag("instruments.piano")
    }

    @Test
    fun pianoKeyboardReachesTheTabBar() {
        launch("qa-tab" to "piano")
        val keyboard = waitForTag("piano.keyboard").visibleBounds
        val tabs = waitForTag("tabs.instruments").visibleBounds
        // O teclado desce até a barra de abas: nada de faixa morta no meio
        // (o iOS aceita até 24 pt de folga; aqui 24 dp).
        val gapDp = (tabs.top - keyboard.bottom) / density
        assertTrue("teclado por baixo da barra (folga $gapDp dp)", gapDp > -1f)
        assertTrue("sobram $gapDp dp entre a última tecla e a barra de abas", gapDp <= 24f)
    }

    @Test
    fun sampleBanksLoadAndPlayWithoutKillingTheAudioEngine() {
        launch("qa-tab" to "piano")
        val keyboard = waitForTag("piano.keyboard", 30_000)
        // Uma tecla e um pad: se o motor cair, o processo cai com ele.
        device.click(keyboard.visibleCenter.x, keyboard.visibleCenter.y)
        sleep(400)
        tap("instruments.back")
        tap("instruments.drums")
        val pads = waitForTag("drums.pads")
        device.click(pads.visibleCenter.x, pads.visibleCenter.y)
        sleep(400)
        waitForTag("drums.pads")
    }

    @Test
    fun scalesReachableFromTheHub() {
        launch("qa-tab" to "instruments")
        tap("instruments.scales")
        waitForTag("scales.play")
    }

    @Test
    fun studyToolsLiveInTheHubAndFollowTheInstrument() {
        launch("qa-tab" to "chords")
        waitForTag("study.instrument.piano")
        tap("study.instrument.violao")
        waitForTag("chords.play")
        tap("study.instrument.viola")
        waitForTag("chords.play")
    }

    // ---- Cordas ----

    @Test
    fun cordasPlaysWithoutKillingTheAudioEngine() {
        launch("qa-tab" to "cordas", "qa-cordas-selftest" to true)
        waitForTag("cordas.neck", 20_000)
        sleep(3_000)
        waitForTag("cordas.neck")
    }

    @Test
    fun strummingFromTheEdgeNeverLeavesTheCordasScreen() {
        launch("qa-tab" to "cordas", "qa-cordas-strummed" to true)
        val neck = waitForTag("cordas.neck", 20_000).visibleBounds
        // Uma batida que começa colada na borda esquerda é batida, não "voltar".
        device.swipe(neck.left + 2, neck.centerY(), neck.left + neck.width() * 4 / 5, neck.centerY(), 12)
        sleep(600)
        waitForTag("cordas.neck")
    }

    @Test
    fun releasingAFretPutsTheStringBackToOpen() {
        launch("qa-tab" to "cordas", "qa-cordas-hands-free" to true)
        val neck = waitForTag("cordas.neck", 20_000).visibleBounds
        val before = stateDescription("cordas.neck")
        // Segurar um dedo numa casa: um "swipe" parado de ~1 s. O estado tem
        // que voltar ao de corda solta quando o dedo sai.
        val x = neck.left + neck.width() / 2
        val y = neck.top + (neck.height() * 0.45f).toInt()
        device.swipe(arrayOf(Point(x, y), Point(x, y)), 60)
        sleep(400)
        val after = stateDescription("cordas.neck")
        assertEquals("soltar tinha que voltar à corda solta", before, after)
    }

    // ---- Separar ----

    @Test
    fun stemsOpensOnTheLibrary() {
        launch("qa-tab" to "stems")
        waitForTag("library.local")
        assertTrue(tagExists("library.connect") || tagExists("library.roqueos"))
    }

    @Test
    fun stemsPlayerSurvivesPlaybackAndOpensMixer() {
        launch("qa-tab" to "stems", "qa-stems-demo" to true)
        waitForTag("stems.play", 60_000).click()
        sleep(2_500)
        val elapsed = waitForTag("stems.elapsed").text
        assertTrue("o relógio não andou: $elapsed", elapsed != null && elapsed != "0:00")
        tap("stems.mixer")
        tap("stems.solo.drums")
        tap("stems.mute.bass")
        waitForTag("stems.solo.drums")
    }

    @Test
    fun stemsRemembersSpeedAcrossRelaunch() {
        launch("qa-tab" to "stems", "qa-stems-demo" to true, "qa-reset" to true)
        waitForTag("stems.play", 60_000)
        openMixerExpanded()
        // O `qa-reset` não apaga a memória de mesa: a velocidade pode vir de
        // uma rodada anterior. O teste parte do que a tela mostra e soma dois
        // passos de 0,05 (voltando para 1,00x antes, se preciso).
        waitForTag("stems.speedValue")
        while (textOf("stems.speedValue") != "1,00x") {
            val before = textOf("stems.speedValue")
            val above = (before ?: "1,00x").removeSuffix("x").replace(',', '.').toDouble() > 1
            tap(if (above) "stems.speed.minus" else "stems.speed.plus")
            val deadline = System.currentTimeMillis() + 3_000
            while (textOf("stems.speedValue") == before && System.currentTimeMillis() < deadline) sleep(200)
            check(textOf("stems.speedValue") != before, "a velocidade não saiu de $before")
        }
        tap("stems.speed.plus")
        check(waitForTagText("stems.speedValue", "1,05x", 5_000), "velocidade não foi a 1,05x (diz '${textOf("stems.speedValue")}')")
        tap("stems.speed.plus")
        check(waitForTagText("stems.speedValue", "1,10x", 5_000), "velocidade não foi a 1,10x (diz '${textOf("stems.speedValue")}')")
        device.pressBack()
        tap("stems.back")
        launch("qa-tab" to "stems", "qa-stems-demo" to true)
        waitForTag("stems.play", 60_000)
        openMixerExpanded()
        check(waitForTagText("stems.speedValue", "1,10x", 5_000), "velocidade não voltou em 1,10x (diz '${textOf("stems.speedValue")}')")
    }

    @Test
    fun stemsPracticeLoopRepeatsTheMarkedSection() {
        launch("qa-tab" to "stems", "qa-stems-demo" to true)
        waitForTag("stems.play", 60_000).click()
        sleep(1_500)
        // Um toque em 32 rodadas não pegou (f15k): repete uma vez se "A" não
        // aparecer, e falha se o segundo toque tiver ido além (A-B).
        waitForTag("stems.loop").click() // A
        if (!device.wait(Until.hasObject(By.text("A")), 2_000)) {
            check(!device.hasObject(By.text("A-B")), "o primeiro toque no loop chegou atrasado")
            device.findObject(byTag("stems.loop"))!!.click()
        }
        waitForText("A", 3_000)
        sleep(2_500)
        device.findObject(byTag("stems.loop"))!!.click() // B: o loop liga
        waitForText("A-B", 3_000)
        // O relógio tem que voltar para perto de A dentro de um ciclo.
        var wrapped = false
        var last = -1
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 12_000 && !wrapped) {
            sleep(400)
            val text = device.findObject(byTag("stems.elapsed"))?.text ?: continue
            val parts = text.split(':')
            if (parts.size != 2) continue
            val seconds = parts[0].toInt() * 60 + parts[1].toInt()
            if (last >= 0 && seconds < last) wrapped = true
            last = seconds
        }
        assertTrue("o loop A/B não voltou", wrapped)
    }

    @Test
    fun everySeparatedSongIsReachableFromTheRecentList() {
        launch("qa-tab" to "stems", "qa-stems-many" to true, "qa-reset" to true)
        waitForTag("library.recent.0")
        waitForTag("library.recent.5")
        assertFalse(tagExists("library.recent.6"))
        tap("library.recent.showAll")
        waitForTag("library.recent.6")
        waitForTag("library.recent.11")
    }

    @Test
    fun setlistCreateAddAndPlayFromIt() {
        launch("qa-tab" to "stems", "qa-stems-demo" to true, "qa-reset" to true)
        waitForTag("stems.play", 60_000)
        tap("stems.back")
        waitForTag("setlists.name").text = "Show de sabado"
        tap("setlists.new")
        waitForText("Show de sabado")
        // Abre o cartão do repertório (o iOS toca na linha) e adiciona a demo.
        waitForTag("setlists.row.0")
        waitForText("Show de sabado").click()
        addToSetlist("Cadentia Demo")
        tap("setlist.song.0")
        waitForTag("stems.play", 30_000)
        waitForTag("stems.queuePosition")
    }

    @Test
    fun setlistPlaysInOrderAndAutoAdvances() {
        launch("qa-tab" to "stems", "qa-stems-demo" to true, "qa-stems-demo2" to true, "qa-reset" to true)
        waitForTag("stems.play", 60_000)
        tap("stems.back")
        waitForTag("setlists.name").text = "Ordem"
        tap("setlists.new")
        waitForTag("setlists.row.0")
        waitForText("Ordem").click()
        // "Demo 2" primeiro: "Cadentia Demo" também casa com a segunda demo.
        addToSetlist("Demo 2")
        addToSetlist("Cadentia Demo")
        waitForTag("setlist.song.1")
        tap("setlist.playOrdered")
        waitForTag("stems.play", 30_000)
        waitForText("1/2", 10_000)
        tap("stems.queueNext")
        waitForText("2/2", 10_000)
        tap("stems.queuePrev")
        waitForText("1/2", 10_000)
    }

    // ---- Mais ----

    @Test
    fun studioToneToggles() {
        launch("qa-tab" to "studio")
        waitForTag("studio.scope")
        waitForTag("studio.toggle").click()
        waitForText(string(R.string.music_metronome_stop), 3_000)
        waitForTag("studio.toggle").click()
        waitForText(string(R.string.music_metronome_start), 3_000)
    }

    @Test
    fun tablatureDemoLoadsAndPlays() {
        launch("qa-tab" to "tab", "qa-reset" to true)
        waitForText("Cadentia Demo")
        waitForTag("tab.toggle", 5_000).click()
        waitForText(string(R.string.tablature_stop), 10_000)
        waitForTag("tab.toggle").click()
        waitForText(string(R.string.tablature_play), 10_000)
        tap("tab.mixer")
        waitForText("Solo", 5_000)
    }

    @Test
    fun recorderOpensFromMore() {
        launch("qa-tab" to "recorder")
        waitForTag("recorder.record")
        waitForTag("recorder.studioMode")
        waitForTag("recorder.play")
    }

    @Test
    fun aboutShowsVersionAndThirdPartyLicense() {
        launch("qa-tab" to "about")
        waitForTag("about.version", 30_000)
        waitForText("Roque Ribeiro")
        waitForText("Rafael Luques")
        waitForTag("about.linkedin.roque")
        waitForTag("about.linkedin.rafael")
        // Mais abaixo: o crédito do Cordas e as licenças (numa folha, como no iOS).
        waitForText("Phelipi Dal Olio")
        waitForTag("about.linkedin.phelipi")
        waitForText(string(R.string.cadentia_about_link_licenses)).click()
        waitForTag("about.licenses", 5_000)
        waitFor(By.textContains("MIT"), "o aviso de copyright do Demucs", 10_000)
    }

    @Test
    fun soundSettingsOpenFromMoreAndClose() {
        launch("qa-tab" to "more")
        tap("more.sound")
        waitForTag("sound.sheet")
        for (family in listOf("guitar", "bass", "drums", "keys")) waitForTag("sound.$family")
        tap("sound.close")
        assertTrue(device.wait(Until.gone(byTag("sound.sheet")), 5_000))
    }

    @Test
    fun tabSwitchKeepsWorking() {
        launch()
        waitForTag("tuner.gauge")
        tap("tabs.metronome")
        waitForTag("metronome.toggle")
        tap("tabs.instruments")
        waitForTag("instruments.piano")
        tap("tabs.stems")
        waitForTag("library.local")
        tap("tabs.more")
        waitForTag("more.sound")
        tap("tabs.tuner")
        waitForTag("tuner.gauge")
    }

    @Test
    fun appStaysAliveAfterHomeAndBack() {
        launch("qa-tab" to "metronome")
        waitForTag("metronome.toggle").click()
        waitForText(string(R.string.music_metronome_stop), 3_000)
        device.pressHome()
        sleep(2_000)
        launch("qa-tab" to "metronome")
        waitForTag("metronome.toggle")
    }
}
