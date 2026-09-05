package com.levelhard.cadentia.features.more

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.BuildConfig
import com.levelhard.cadentia.MoreDestination
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.kit.enabledSampleFamilies
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.DestinationChrome
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.WordmarkFamily
import com.levelhard.cadentia.ui.pageTransition

/**
 * A quinta aba — cards premium no lugar da lista "Mais" do sistema, espelho
 * do `MoreView.swift`. Os destinos empurram dentro desta pilha; o botão
 * físico de voltar sai do destino antes de sair do app.
 */
@Composable
fun MoreScreen(
    store: com.levelhard.cadentia.settings.SettingsStore,
    destination: MoreDestination?,
    onDestinationChange: (MoreDestination?) -> Unit,
) {
    BackHandler(enabled = destination != null) { onDestinationChange(null) }

    // Remover animações (Reduce Motion) → troca seca, sem fade/scale.
    val reduceMotion = com.levelhard.cadentia.ui.rememberReduceMotion()
    AnimatedContent(
        targetState = destination,
        label = "more-destination",
        transitionSpec = {
            if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut()
            }
        },
    ) { dest ->
        when (dest) {
            null -> MoreList(store = store, onOpen = onDestinationChange)
            // A barra de voltar + título que o `NavigationStack` do iOS dá a
            // cada destino do Mais. Tablatura e Frequência mostram só o
            // voltar, como lá (a Tablatura tem o nome da música no cabeçalho).
            MoreDestination.Recorder -> DestinationChrome(
                stringResource(R.string.cadentia_recorder_title), CzTokens.recorderCyan,
                onBack = { onDestinationChange(null) }, backTag = "more.back",
            ) { com.levelhard.cadentia.features.recorder.RecorderScreen(store) }
            MoreDestination.Tablature -> DestinationChrome(
                null, CzTokens.tabIndigo,
                onBack = { onDestinationChange(null) }, backTag = "more.back",
            ) { com.levelhard.cadentia.features.tab.TablatureScreen(store) }
            MoreDestination.Studio -> DestinationChrome(
                null, CzTokens.studioPurple,
                onBack = { onDestinationChange(null) }, backTag = "more.back",
            ) { com.levelhard.cadentia.features.studio.StudioScreen(store) }
            MoreDestination.About -> DestinationChrome(
                stringResource(R.string.cadentia_about_title), CzTokens.stemsTeal,
                onBack = { onDestinationChange(null) }, backTag = "more.back",
            ) { AboutScreen() }
        }
    }
}

@Composable
private fun MoreList(store: com.levelhard.cadentia.settings.SettingsStore, onOpen: (MoreDestination) -> Unit) {
    var showSound by rememberSaveable { mutableStateOf(false) }
    if (showSound) SoundSettingsSheet(store) { showSound = false }

    // O resumo do cartão de Som: quantas famílias já estão em sample, sem
    // obrigar a abrir a folha. Sem banco instalado é "síntese", que é o que
    // o iOS mostra nesse estado.
    val settings by store.settings.collectAsState()
    val installedFamilies = remember { SampleBank.shared.installedFamilies }
    val sampledCount = settings.enabledSampleFamilies.count { it in installedFamilies }
    val soundDetail = when {
        installedFamilies.isEmpty() || sampledCount == 0 -> stringResource(R.string.cadentia_sound_mode_synth).lowercase()
        else -> "$sampledCount/${installedFamilies.size} · " + stringResource(R.string.cadentia_sound_mode_samples).lowercase()
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = CzTokens.gold)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FeatureCard(
                    titleRes = R.string.cadentia_recorder_title,
                    iconRes = R.drawable.ic_more_recorder,
                    color = CzTokens.recorderCyan,
                    detail = "multitrack", // i18n-verbatim: termo técnico, igual nos 10
                    tag = "more.recorder",
                ) { onOpen(MoreDestination.Recorder) }
                FeatureCard(
                    titleRes = R.string.tablature_title,
                    iconRes = R.drawable.ic_more_tablature,
                    color = CzTokens.tabIndigo,
                    detail = "rostab", // i18n-verbatim: nome do formato
                    tag = "more.tablature",
                ) { onOpen(MoreDestination.Tablature) }
                FeatureCard(
                    titleRes = R.string.music_tabs_frequency,
                    iconRes = R.drawable.ic_more_frequency,
                    color = CzTokens.studioPurple,
                    detail = "20Hz–20kHz", // i18n-verbatim: unidade física
                    tag = "more.studio",
                ) { onOpen(MoreDestination.Studio) }
                FeatureCard(
                    titleRes = R.string.cadentia_sound_title,
                    iconRes = R.drawable.ic_more_sound,
                    color = CzTokens.gold,
                    detail = soundDetail,
                    tag = "more.sound",
                ) { showSound = true }
                FeatureCard(
                    titleRes = R.string.cadentia_about_title,
                    iconRes = R.drawable.ic_more_about,
                    color = CzTokens.stemsTeal,
                    detail = "RoqueOS", // i18n-verbatim: marca
                    tag = "more.about",
                ) { onOpen(MoreDestination.About) }
            }

            // Rodapé da marca — quieto, premium.
            Spacer(Modifier.height(28.dp))
            Icon(
                painter = painterResource(R.drawable.ic_tab_tuner),
                contentDescription = null,
                tint = CzTokens.gold.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = "Cadentia", // i18n-verbatim: wordmark
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = WordmarkFamily,
                color = CzTokens.textSecondary,
            )
            Text(
                text = "v" + BuildConfig.VERSION_NAME, // i18n-verbatim: versão
                style = MaterialTheme.typography.labelMedium,
                color = CzTokens.textTertiary,
            )
        }
    }
}

@Composable
private fun FeatureCard(
    titleRes: Int,
    iconRes: Int,
    color: Color,
    detail: String,
    tag: String,
    onClick: () -> Unit,
) {
    val title = stringResource(titleRes)
    // Port do `featureCardBody`: círculo de 54 pt com anel na cor do destino,
    // e a borda do cartão puxando essa cor no canto de cima.
    val shape = RoundedCornerShape(CzTokens.radiusLG)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .background(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.03f))),
                shape,
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(color.copy(alpha = 0.35f), CzTokens.hairline)),
                shape,
            )
            .clip(shape),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = title, onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .background(color.copy(alpha = 0.16f), CircleShape)
                    .border(1.dp, color.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textPrimary,
                )
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = CzTokens.textTertiary,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = CzTokens.textTertiary,
            )
        }
    }
}
