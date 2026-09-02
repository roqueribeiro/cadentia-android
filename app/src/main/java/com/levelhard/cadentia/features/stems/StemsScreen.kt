package com.levelhard.cadentia.features.stems

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.FeatureHero
import com.levelhard.cadentia.ui.pageTransition

/** Separar — fase 4 traz o separador ONNX, a fila em série e as fontes RoqueOS. */
@Composable
fun StemsScreen() {
    FeatureHero(
        titleRes = R.string.cadentia_stems_tab_title,
        icon = painterResource(R.drawable.ic_tab_stems),
        accent = CzTokens.stemsTeal,
        modifier = Modifier.pageTransition(),
    )
}
