package com.levelhard.cadentia.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * "Reduce Motion e VoiceOver são inegociáveis." No Android o sinal é a escala
 * de animação do sistema zerada (Remover animações, em Acessibilidade).
 * Toda animação decorativa consulta isto e vira estado parado.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}
