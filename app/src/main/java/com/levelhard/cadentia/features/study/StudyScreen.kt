package com.levelhard.cadentia.features.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.I18nMap
import com.levelhard.cadentia.LocalQaFlags
import com.levelhard.cadentia.R
import com.levelhard.cadentia.audio.PolyphonicSampler
import com.levelhard.cadentia.kit.InstrumentSynth
import com.levelhard.cadentia.kit.InstrumentVoice
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.kit.cordas.CordaInstrument
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition

/** Acordes ou Escalas — o que a tela de Estudo mostra. */
enum class StudyKind { Chords, Scales }

/**
 * Estudo — port do `StudyView.swift` (1.16): Acordes e Escalas fora do Piano,
 * porque acorde e escala não pertencem a um instrumento, pertencem a quem toca
 * qualquer um deles. O seletor no topo é a tese inteira: um dó maior é um dó
 * maior, e o instrumento é só como ele é desenhado — e como ele soa, porque o
 * seletor mudar o desenho e deixar o som no piano é a meia-verdade que faz a
 * pessoa desconfiar da tela inteira.
 */
@Composable
fun StudyScreen(store: SettingsStore, kind: StudyKind) {
    val accent = CzTokens.gold
    val scope = rememberCoroutineScope()
    val qa = LocalQaFlags.current
    val sampler = remember { PolyphonicSampler() }
    // `null` = piano. Em memória e não nas configurações: a escolha aqui é do
    // momento do estudo, não uma preferência do app. `qa-study-instrument
    // baixo` abre já no instrumento, para o print sair sem tocar no seletor.
    var instrumentId by rememberSaveable { mutableStateOf(qa.studyInstrument) }
    val instrument = instrumentId?.let { id -> CordaInstrument.all.firstOrNull { it.id == id } }
    val voice = instrument?.let { InstrumentVoice.from(it.sampleVoice) } ?: InstrumentVoice.AcousticPiano

    /** Nota avulsa: toca a duração pedida e morre só. */
    fun playNote(frequency: Double, duration: Double) {
        if (!sampler.startIfNeeded()) return
        val rate = sampler.sampleRate
        sampler.play("${SampleBank.shared.soundGeneration}/study/${voice.id}/$frequency/$duration") {
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
                InstrumentPicker(kind, instrument, accent) { instrumentId = it?.id }
                when (kind) {
                    StudyKind.Chords -> ChordsPane(store, accent, ::playNote, scope, instrument)
                    StudyKind.Scales -> ScalesPane(store, accent, ::playNote, scope, instrument)
                }
            }
        }
    }
}

/**
 * Piano e os instrumentos do Cordas. Nos Acordes, só quem toca acorde: o
 * baixo toca uma nota de cada vez, e a forma dele emudece três das quatro
 * cordas — um diagrama disso não ensina nada. Nas Escalas ele fica.
 */
@Composable
private fun InstrumentPicker(kind: StudyKind, current: CordaInstrument?, accent: Color, onPick: (CordaInstrument?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
    ) {
        InstrumentOption(stringResource(R.string.music_tabs_piano), current == null, accent, "study.instrument.piano") { onPick(null) }
        for (candidate in CordaInstrument.all.filter { kind == StudyKind.Scales || it.playsChords }) {
            InstrumentOption(
                stringResource(I18nMap.res(candidate.nameKey)), current?.id == candidate.id, accent,
                "study.instrument.${candidate.id}",
            ) { onPick(candidate) }
        }
    }
}

@Composable
private fun InstrumentOption(text: String, active: Boolean, accent: Color, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) accent.copy(alpha = 0.18f) else CzTokens.surface,
        contentColor = if (active) accent else CzTokens.textSecondary,
        border = BorderStroke(1.dp, if (active) accent.copy(alpha = 0.42f) else CzTokens.hairline),
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
        )
    }
}
