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
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CadentiaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val qa = QaFlags(
            tab = intent.getStringExtra("qa-tab"),
            noSplash = intent.getBooleanExtra("qa-no-splash", false),
            tunerSilent = intent.getBooleanExtra("qa-tuner-silent", false),
            reset = intent.getBooleanExtra("qa-reset", false),
            edit = intent.getBooleanExtra("qa-edit", false),
            showCatalog = intent.getBooleanExtra("qa-show-catalog", false),
            studioAutoplay = intent.getBooleanExtra("qa-studio-autoplay", false),
        )

        val store = SettingsStore(applicationContext)
        // `-qa-reset` do iOS: testes partem do estado de fábrica (um tap-tempo
        // do run anterior vazaria para as asserções de BPM do próximo).
        if (qa.reset) store.reset()

        var initialTab = CadentiaTab.entries.firstOrNull { it.qaName == qa.tab } ?: CadentiaTab.Tuner
        var initialDestination: MoreDestination? = null
        // Studio/Tab/Recorder/Piano/About vivem dentro do Mais.
        MoreDestination.entries.firstOrNull { it.qaName == qa.tab }?.let {
            initialTab = CadentiaTab.More
            initialDestination = it
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
