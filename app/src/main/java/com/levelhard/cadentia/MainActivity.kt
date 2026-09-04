package com.levelhard.cadentia

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.kit.enabledSampleFamilies
import com.levelhard.cadentia.settings.SettingsStore
import kotlinx.coroutines.launch
import com.levelhard.cadentia.ui.CadentiaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val qa = QaFlags(
            tab = intent.getStringExtra("qa-tab"),
            noSplash = intent.getBooleanExtra("qa-no-splash", false),
            tunerSilent = intent.getBooleanExtra("qa-tuner-silent", false),
            tunerDemo = intent.getBooleanExtra("qa-tuner-demo", false),
            reset = intent.getBooleanExtra("qa-reset", false),
            edit = intent.getBooleanExtra("qa-edit", false),
            showCatalog = intent.getBooleanExtra("qa-show-catalog", false),
            studioAutoplay = intent.getBooleanExtra("qa-studio-autoplay", false),
            cordasMode = intent.getStringExtra("qa-cordas-mode"),
            cordasInstrument = intent.getStringExtra("qa-cordas-instrument"),
            cordasHandsFree = when {
                intent.getBooleanExtra("qa-cordas-hands-free", false) -> true
                intent.getBooleanExtra("qa-cordas-strummed", false) -> false
                else -> null
            },
            cordasPanel = intent.getBooleanExtra("qa-cordas-panel", false),
            cordasSelftest = intent.getBooleanExtra("qa-cordas-selftest", false),
            cordasCoach = intent.getBooleanExtra("qa-cordas-coach", false),
            cordasReplay = intent.getBooleanExtra("qa-cordas-replay", false),
            studyInstrument = intent.getStringExtra("qa-study-instrument"),
            stemsProgress = intent.getStringExtra("qa-stems-progress")?.toIntOrNull(),
            stemsBatch = intent.getStringExtra("qa-stems-batch")?.toIntOrNull(),
            stemsMany = intent.getBooleanExtra("qa-stems-many", false),
            stemsBanner = intent.getStringExtra("qa-stems-banner")?.toIntOrNull(),
            stemsFile = intent.getStringExtra("qa-stems-file"),
        )

        val store = SettingsStore(applicationContext)
        // `-qa-reset` do iOS: testes partem do estado de fábrica (um tap-tempo
        // do run anterior vazaria para as asserções de BPM do próximo).
        if (qa.reset) store.reset()

        // A chave síntese × sample por família segue as configurações: já na
        // abertura e a cada mudança (a folha de Som vira o interruptor).
        SampleBank.shared.setEnabled(store.settings.value.enabledSampleFamilies)
        lifecycleScope.launch {
            store.settings.collect { SampleBank.shared.setEnabled(it.enabledSampleFamilies) }
        }

        var initialTab = CadentiaTab.entries.firstOrNull { it.qaName == qa.tab } ?: CadentiaTab.Tuner
        var initialDestination: MoreDestination? = null
        var initialInstrument: InstrumentDestination? = null
        // Studio/Tab/Recorder/About vivem dentro do Mais.
        MoreDestination.entries.firstOrNull { it.qaName == qa.tab }?.let {
            initialTab = CadentiaTab.More
            initialDestination = it
        }
        // Os instrumentos deixaram de morar em Mais e na barra. Os apelidos
        // (piano, drums, chords, scales) continuam valendo para o QA e para
        // atalho antigo; o destino é que mudou (1.16).
        InstrumentDestination.entries.firstOrNull { it.qaName == qa.tab }?.let {
            initialTab = CadentiaTab.Instruments
            initialInstrument = it
        }
        val skipSplash = qa.noSplash || qa.tab != null

        setContent {
            CadentiaTheme {
                CompositionLocalProvider(LocalQaFlags provides qa) {
                    var showSplash by remember { mutableStateOf(!skipSplash) }
                    RootView(
                        store = store,
                        initialTab = initialTab,
                        initialMoreDestination = initialDestination,
                        initialInstrumentDestination = initialInstrument,
                    )
                    AnimatedVisibility(visible = showSplash, exit = fadeOut()) {
                        SplashOverlay { showSplash = false }
                    }
                }
            }
        }
    }

    // Toda tela daqui é usada com as duas mãos no instrumento: a tela não
    // apaga enquanto o app está na frente (o isIdleTimerDisabled do iOS,
    // seguindo o scenePhase).
    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
}
