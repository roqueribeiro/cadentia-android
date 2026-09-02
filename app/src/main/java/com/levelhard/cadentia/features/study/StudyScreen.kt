package com.levelhard.cadentia.features.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.InstrumentSynth
import com.levelhard.cadentia.kit.InstrumentVoice
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition

/** Acordes ou Escalas — o que a tela de Estudo mostra. */
enum class StudyKind { Chords, Scales }

/**
 * Estudo — port do `StudyView.swift` (1.16): Acordes e Escalas fora do Piano,
 * porque acorde e escala não pertencem a um instrumento, pertencem a quem toca
 * qualquer um deles. O seletor de instrumento (piano × cordas) e a voz do
 * instrumento escolhido chegam com o Kit do Cordas na fase 8; até lá o som é
 * o piano acústico, como era dentro do Piano.
 */
@Composable
fun StudyScreen(store: SettingsStore, kind: StudyKind) {
    val accent = CzTokens.gold
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsState()
    val sampler = remember { PolyphonicSampler() }
    val voice = InstrumentVoice.from(settings.piano.voice) ?: InstrumentVoice.AcousticPiano

    /** Nota avulsa: toca a duração pedida e morre só. */
    fun playNote(frequency: Double, duration: Double) {
        if (!sampler.startIfNeeded()) return
        val rate = sampler.sampleRate
        sampler.play("study/${voice.id}/$frequency/$duration") {
            InstrumentSynth.render(
                voice, frequency, duration,
                velocity = 0.85f, gain = 0.7f, sampleRate = rate,
            ).interleaved()
        }
    }

    DisposableEffect(Unit) {
        onDispose { sampler.stop() }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (kind) {
                    StudyKind.Chords -> ChordsPane(store, accent, ::playNote, scope)
                    StudyKind.Scales -> ScalesPane(store, accent, ::playNote, scope)
                }
            }
        }
    }
}
