package com.levelhard.cadentia

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.levelhard.cadentia.ui.CadentiaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ajudantes de QA, os mesmos nomes dos launch args do iOS:
        //   adb shell am start -n com.levelhard.cadentia.debug/com.levelhard.cadentia.MainActivity \
        //     -e qa-tab metronome --ez qa-no-splash true
        val qaTab = intent.getStringExtra("qa-tab")
        val qaNoSplash = intent.getBooleanExtra("qa-no-splash", false)

        var initialTab = CadentiaTab.entries.firstOrNull { it.qaName == qaTab } ?: CadentiaTab.Tuner
        var initialDestination: MoreDestination? = null
        // Studio/Tab/Recorder/Piano/About vivem dentro do Mais.
        MoreDestination.entries.firstOrNull { it.qaName == qaTab }?.let {
            initialTab = CadentiaTab.More
            initialDestination = it
        }
        val skipSplash = qaNoSplash || qaTab != null

        setContent {
            CadentiaTheme {
                var showSplash by remember { mutableStateOf(!skipSplash) }
                RootView(
                    initialTab = initialTab,
                    initialMoreDestination = initialDestination,
                )
                AnimatedVisibility(visible = showSplash, exit = fadeOut()) {
                    SplashOverlay { showSplash = false }
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
