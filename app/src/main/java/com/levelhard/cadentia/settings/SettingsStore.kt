package com.levelhard.cadentia.settings

import android.content.Context
import android.content.SharedPreferences
import com.levelhard.cadentia.kit.AppSettings
import com.levelhard.cadentia.kit.SettingsCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * O store de ajustes — o papel do `SettingsStore` do iOS. Síncrono e barato
 * (os settings têm poucas centenas de bytes; toda mutação persiste já), com
 * `StateFlow` no lugar do @Observable: a tela recompõe a cada `update`.
 * O contrato de codificação (tolerância, sanitize) vive no :kit e é testado lá.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cadentia", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(SettingsCodec.decode(prefs.getString(SettingsCodec.PREFS_KEY, null)))
    val settings: StateFlow<AppSettings> = _settings

    fun update(mutate: (AppSettings) -> Unit) {
        // AppSettings tem vars internas: copia via codec para nunca mutar o
        // valor já publicado no flow (recomposição depende de identidade nova).
        val next = SettingsCodec.decode(SettingsCodec.encode(_settings.value))
        mutate(next)
        next.sanitize()
        _settings.value = next
        prefs.edit().putString(SettingsCodec.PREFS_KEY, SettingsCodec.encode(next)).apply()
    }

    /** QA: `-e qa-reset true` volta ao estado de fábrica antes da UI subir. */
    fun reset() {
        prefs.edit().remove(SettingsCodec.PREFS_KEY).apply()
        _settings.value = AppSettings()
    }
}
