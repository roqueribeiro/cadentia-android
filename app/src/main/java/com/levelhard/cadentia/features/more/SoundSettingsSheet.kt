package com.levelhard.cadentia.features.more

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.I18nMap
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.kit.SampleFamily
import com.levelhard.cadentia.kit.SamplePack
import com.levelhard.cadentia.kit.enabledSampleFamilies
import com.levelhard.cadentia.kit.setSampled
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.exposeTestTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Som dos instrumentos — port da `SoundSettingsView.swift` (1.16): onde se
 * escolhe, por família de instrumento, entre síntese e sample.
 *
 * A escolha é por família e não por voz porque ninguém quer decidir isso
 * vinte e uma vezes — e porque a comparação que interessa é "o violão está
 * melhor?", não "o violão jazz está melhor?". Cada linha diz de onde o
 * sample veio e sob qual licença: não é rodapé jurídico, é o que impede a
 * próxima pessoa de embarcar um banco de origem duvidosa.
 *
 * Sem pack instalado (este build sem os bancos) a folha mostra o estado
 * honesto: nenhum banco, tudo tocando com a síntese do app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSettingsSheet(store: SettingsStore, onDismiss: () -> Unit) {
    val settings by store.settings.collectAsState()
    val packs = remember { SampleBank.shared.installed }
    val available = remember(packs) { packs.map { it.family }.toSet() }
    val enabled = settings.enabledSampleFamilies
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // Guardado para poder ser cancelado: sem isto, fechar a folha ou virar o
    // interruptor de novo deixava aquecimentos concorrentes rodando.
    var warming by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { warming?.cancel() } }

    /**
     * Aquece fora da thread principal: a primeira nota não pode pagar o
     * disco, e a interface não pode congelar enquanto ele é pago.
     *
     * Com teto, e o teto não é opcional. Ligar "Violão" aquecia os três packs
     * da família: 145,3 MB decodificados num cache de 96 MB (medido no iOS),
     * e o fim da fila expulsava o começo. Metade do cache é o teto: sobra
     * espaço para o que a pessoa estava tocando antes de mexer aqui.
     */
    fun warmUp(sources: List<SamplePack>) {
        warming?.cancel()
        var budget = SampleBank.shared.cacheLimitBytes / 2
        warming = scope.launch(Dispatchers.IO) {
            for (pack in sources) {
                if (!isActive) return@launch
                budget -= SampleBank.shared.warmUp(pack.voice, byteBudget = budget) { !isActive }
                if (budget <= 0) return@launch
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .exposeTestTags()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .testTag("sound.sheet"),
        ) {
            // Título com o X do iOS (`sound.close`, rótulo "Concluir"): a folha
            // também fecha por arraste, mas o botão é o que o TalkBack e o
            // teste alcançam.
            Box(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text(
                    text = stringResource(R.string.cadentia_sound_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                )
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cadentia_about_close),
                    tint = CzTokens.textSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .minimumInteractiveComponentSize()
                        .size(20.dp)
                        .clickable(onClick = onDismiss)
                        .testTag("sound.close"),
                )
            }
            Text(
                text = stringResource(R.string.cadentia_sound_hint),
                fontSize = 13.sp,
                color = CzTokens.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (packs.isEmpty()) {
                SoundEmptyState()
            } else {
                for (family in SampleFamily.entries.filter { it in available }) {
                    val sources = packs.filter { it.family == family }
                    FamilyRow(family = family, on = family in enabled, sources = sources) { newValue ->
                        store.update { it.setSampled(family, newValue) }
                        // O coletor da MainActivity também faz isto; aqui é para
                        // a PRÓXIMA nota já sair com a escolha nova.
                        SampleBank.shared.setEnabled(store.settings.value.enabledSampleFamilies)
                        if (newValue) warmUp(sources)
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                SampleCreditsSection(packs, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun FamilyRow(
    family: SampleFamily,
    on: Boolean,
    sources: List<SamplePack>,
    onToggle: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(CzTokens.radiusMD)
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (on) CzTokens.gold.copy(alpha = 0.10f) else CzTokens.surface, shape)
            .border(1.dp, if (on) CzTokens.gold.copy(alpha = 0.40f) else CzTokens.hairline, shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(I18nMap.res(family.nameKey)),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CzTokens.textPrimary,
                )
                Text(
                    text = stringResource(if (on) R.string.cadentia_sound_mode_samples else R.string.cadentia_sound_mode_synth),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (on) CzTokens.gold else CzTokens.textTertiary,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CzTokens.stageBottom,
                    checkedTrackColor = CzTokens.gold,
                    uncheckedThumbColor = CzTokens.textSecondary,
                    uncheckedTrackColor = CzTokens.surface,
                    uncheckedBorderColor = CzTokens.hairline,
                ),
                modifier = Modifier.testTag("sound.${family.id}"),
            )
        }
        for (pack in sources) {
            // Combinado num elemento só: em três textos soltos o TalkBack lê
            // "Virtuosity", "CC0", "360" como três paradas sem relação.
            val count = stringResource(R.string.cadentia_sound_samples_count, pack.regions.size)
            // Nome e licença são dados do manifesto; só a contagem é frase.
            val spoken = listOf(pack.name, pack.license, count).joinToString() // i18n-verbatim: dados + frase já localizada
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
            ) {
                // Duas linhas, não uma: com uma, "FreePats FSBS Electric Guitar
                // (clean)" e "(jazz)" viravam a mesma linha cortada (QA v5).
                Text(
                    text = pack.name, // i18n-verbatim: nome do banco
                    fontSize = 11.sp,
                    color = CzTokens.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = pack.license, // i18n-verbatim: identificador SPDX
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.tunerGreen.copy(alpha = 0.85f),
                    modifier = Modifier
                        .background(CzTokens.tunerGreen.copy(alpha = 0.12f), CircleShape)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.size(2.dp))
                // Puro, sem atenuar: sobre o cartão dourado o `textTertiary`
                // atenuado media 3,60:1 no iOS, abaixo do 4,5:1 do AA.
                Text(
                    text = "${pack.regions.size}", // i18n-verbatim: número; a voz lê `count`
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = CzTokens.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun SoundEmptyState() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(CzTokens.surface, RoundedCornerShape(CzTokens.radiusMD))
            .padding(16.dp)
            .testTag("sound.empty"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = CzTokens.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.cadentia_sound_empty),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textSecondary,
            )
        }
        Text(
            text = stringResource(R.string.cadentia_sound_empty_hint),
            fontSize = 12.sp,
            color = CzTokens.textTertiary,
        )
    }
}
