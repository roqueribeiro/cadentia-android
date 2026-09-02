package com.levelhard.cadentia.features.metronome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.FeatureHero
import com.levelhard.cadentia.ui.pageTransition

/** Metrônomo — fase 1 traz o clique agendado, polirritmia e tap BPM. */
@Composable
fun MetronomeScreen() {
    FeatureHero(
        titleRes = R.string.music_tabs_metronome,
        icon = painterResource(R.drawable.ic_tab_metronome),
        accent = CzTokens.metronomeAmber,
        modifier = Modifier.pageTransition(),
    )
}
