package com.levelhard.cadentia.features.more

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.BuildConfig
import com.levelhard.cadentia.MoreDestination
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.FeatureHero
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

    AnimatedContent(targetState = destination, label = "more-destination") { dest ->
        when (dest) {
            null -> MoreList(onOpen = onDestinationChange)
            MoreDestination.Piano -> com.levelhard.cadentia.features.piano.PianoScreen(store)
            MoreDestination.Recorder ->
                com.levelhard.cadentia.features.recorder.RecorderScreen(store)
            MoreDestination.Tablature ->
                com.levelhard.cadentia.features.tab.TablatureScreen(store)
            MoreDestination.Studio -> FeatureHero(
                titleRes = R.string.music_tabs_frequency,
                icon = painterResource(R.drawable.ic_more_frequency),
                accent = CzTokens.studioPurple,
                modifier = Modifier.pageTransition(),
            )
            MoreDestination.About -> FeatureHero(
                titleRes = R.string.cadentia_about_title,
                icon = painterResource(R.drawable.ic_more_about),
                accent = CzTokens.stemsTeal,
                modifier = Modifier.pageTransition(),
            )
        }
    }
}

@Composable
private fun MoreList(onOpen: (MoreDestination) -> Unit) {
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
                    titleRes = R.string.music_tabs_piano,
                    iconRes = R.drawable.ic_more_piano,
                    color = CzTokens.gold,
                    detail = "77", // i18n-verbatim: número, não texto
                ) { onOpen(MoreDestination.Piano) }
                FeatureCard(
                    titleRes = R.string.cadentia_recorder_title,
                    iconRes = R.drawable.ic_more_recorder,
                    color = CzTokens.recorderCyan,
                    detail = "multitrack", // i18n-verbatim: termo técnico, igual nos 10
                ) { onOpen(MoreDestination.Recorder) }
                FeatureCard(
                    titleRes = R.string.tablature_title,
                    iconRes = R.drawable.ic_more_tablature,
                    color = CzTokens.tabIndigo,
                    detail = "rostab", // i18n-verbatim: nome do formato
                ) { onOpen(MoreDestination.Tablature) }
                FeatureCard(
                    titleRes = R.string.music_tabs_frequency,
                    iconRes = R.drawable.ic_more_frequency,
                    color = CzTokens.studioPurple,
                    detail = "20Hz–20kHz", // i18n-verbatim: unidade física
                ) { onOpen(MoreDestination.Studio) }
                FeatureCard(
                    titleRes = R.string.cadentia_about_title,
                    iconRes = R.drawable.ic_more_about,
                    color = CzTokens.stemsTeal,
                    detail = "RoqueOS", // i18n-verbatim: marca
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
    onClick: () -> Unit,
) {
    val title = stringResource(titleRes)
    CzCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = title, onClick = onClick)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(46.dp)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(color.copy(alpha = 0.30f), Color.Transparent),
                        ),
                    )
                }
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = CzTokens.textPrimary,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
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
