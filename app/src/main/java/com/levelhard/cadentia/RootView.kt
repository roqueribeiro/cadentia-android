package com.levelhard.cadentia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.levelhard.cadentia.features.instruments.InstrumentsHub
import com.levelhard.cadentia.features.metronome.MetronomeScreen
import com.levelhard.cadentia.features.more.MoreScreen
import com.levelhard.cadentia.features.stems.StemsScreen
import com.levelhard.cadentia.features.tuner.TunerScreen
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzTokens

/**
 * As cinco abas — espelho do `RootView.swift` (1.16). A Bateria cedeu a aba
 * e virou um card do hub de Instrumentos: ela perde um toque; em troca, os
 * instrumentos passam a morar juntos e Acordes e Escalas saem de dentro do
 * Piano. Separar ocupa a barra; cinco é o teto para o item continuar fácil
 * de acertar. O acento da interface muda com a aba, como o `.tint(tab.accent)`.
 */
enum class CadentiaTab(val qaName: String) {
    Tuner("tuner"),
    Metronome("metronome"),
    Instruments("instruments"),
    Stems("stems"),
    More("more");

    val accent: Color
        get() = when (this) {
            Tuner -> CzTokens.tunerGreen
            Metronome -> CzTokens.metronomeAmber
            Instruments -> CzTokens.gold
            Stems -> CzTokens.stemsTeal
            More -> CzTokens.gold
        }
}

/**
 * Destinos dentro de Instrumentos. Os nomes de QA são os apelidos antigos de
 * `-qa-tab` (piano, drums, chords, scales): o atalho continua valendo, o
 * destino é que mudou. `bass` abre o Cordas já no baixo: ele não é uma tela, é
 * um braço com quatro cordas, e o Cordas já é um motor de braço com cordas.
 */
enum class InstrumentDestination(val qaName: String) {
    Cordas("cordas"),
    Piano("piano"),
    Bass("bass"),
    Drums("drums"),
    Chords("chords"),
    Scales("scales"),
}

/** Destinos que vivem dentro do Mais (deep link de QA usa os mesmos nomes). */
enum class MoreDestination(val qaName: String) {
    Recorder("recorder"),
    Tablature("tab"),
    Studio("studio"),
    About("about"),
}

@Composable
fun RootView(
    store: SettingsStore,
    initialTab: CadentiaTab = CadentiaTab.Tuner,
    initialMoreDestination: MoreDestination? = null,
    initialInstrumentDestination: InstrumentDestination? = null,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var moreDestination by rememberSaveable { mutableStateOf(initialMoreDestination) }
    var instrumentDestination by rememberSaveable { mutableStateOf(initialInstrumentDestination) }

    Scaffold(
        containerColor = CzTokens.stageBottom,
        bottomBar = {
            NavigationBar(
                containerColor = CzTokens.stageTop.copy(alpha = 0.96f),
                contentColor = CzTokens.textSecondary,
            ) {
                CadentiaTab.entries.forEach { entry ->
                    val accent = entry.accent
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = {
                            if (tab == entry) {
                                // Tocar de novo na aba atual volta à raiz dela,
                                // como o TabView do iOS faz com a pilha.
                                when (entry) {
                                    CadentiaTab.Instruments -> instrumentDestination = null
                                    CadentiaTab.More -> moreDestination = null
                                    else -> Unit
                                }
                                return@NavigationBarItem
                            }
                            if (entry != CadentiaTab.More) moreDestination = null
                            if (entry != CadentiaTab.Instruments) instrumentDestination = null
                            tab = entry
                        },
                        icon = {
                            Icon(
                                painter = painterResource(entry.iconRes),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(entry.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = accent,
                            selectedTextColor = accent,
                            indicatorColor = accent.copy(alpha = 0.14f),
                            unselectedIconColor = CzTokens.textTertiary,
                            unselectedTextColor = CzTokens.textTertiary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            key(tab) {
                when (tab) {
                    CadentiaTab.Tuner -> TunerScreen(store)
                    CadentiaTab.Metronome -> MetronomeScreen(store)
                    CadentiaTab.Instruments -> InstrumentsHub(
                        store = store,
                        destination = instrumentDestination,
                        onDestinationChange = { instrumentDestination = it },
                    )
                    CadentiaTab.Stems -> StemsScreen()
                    CadentiaTab.More -> MoreScreen(
                        store = store,
                        destination = moreDestination,
                        onDestinationChange = { moreDestination = it },
                    )
                }
            }
        }
    }
}

private val CadentiaTab.labelRes: Int
    get() = when (this) {
        CadentiaTab.Tuner -> R.string.music_tabs_tuner
        CadentiaTab.Metronome -> R.string.music_tabs_metronome
        CadentiaTab.Instruments -> R.string.cadentia_instruments_title
        CadentiaTab.Stems -> R.string.cadentia_stems_tab_title
        CadentiaTab.More -> R.string.cadentia_tabs_more
    }

private val CadentiaTab.iconRes: Int
    get() = when (this) {
        CadentiaTab.Tuner -> R.drawable.ic_tab_tuner
        CadentiaTab.Metronome -> R.drawable.ic_tab_metronome
        CadentiaTab.Instruments -> R.drawable.ic_tab_instruments
        CadentiaTab.Stems -> R.drawable.ic_tab_stems
        CadentiaTab.More -> R.drawable.ic_tab_more
    }
