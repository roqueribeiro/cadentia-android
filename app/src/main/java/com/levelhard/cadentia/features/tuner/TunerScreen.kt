package com.levelhard.cadentia.features.tuner

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.FeatureHero
import com.levelhard.cadentia.ui.pageTransition

/** Afinador — fase 1 traz o YIN, o ring gauge e as afinações. */
@Composable
fun TunerScreen() {
    FeatureHero(
        titleRes = R.string.music_tabs_tuner,
        icon = painterResource(R.drawable.ic_tab_tuner),
        accent = CzTokens.tunerGreen,
        modifier = Modifier.pageTransition(),
    )
}
