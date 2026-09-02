package com.levelhard.cadentia.features.drums

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.FeatureHero
import com.levelhard.cadentia.ui.pageTransition

/** Bateria — fase 2 traz os pads 3×3, o sequencer e os 25 grooves. */
@Composable
fun DrumsScreen() {
    FeatureHero(
        titleRes = R.string.music_tabs_drums,
        icon = painterResource(R.drawable.ic_tab_drums),
        accent = CzTokens.danger,
        modifier = Modifier.pageTransition(),
    )
}
