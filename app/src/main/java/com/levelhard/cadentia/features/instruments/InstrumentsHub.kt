package com.levelhard.cadentia.features.instruments

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.InstrumentDestination
import com.levelhard.cadentia.R
import com.levelhard.cadentia.features.drums.DrumsScreen
import com.levelhard.cadentia.features.piano.PianoScreen
import com.levelhard.cadentia.features.study.StudyKind
import com.levelhard.cadentia.features.study.StudyScreen
import com.levelhard.cadentia.settings.SettingsStore
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.pageTransition
import com.levelhard.cadentia.ui.rememberReduceMotion

/**
 * Um lugar só para tocar — port do `InstrumentsHub.swift` (1.16).
 *
 * Antes, os instrumentos moravam em três lugares: a Bateria tinha aba própria,
 * Piano era um card dentro de Mais. Nada dizia que eram a mesma categoria de
 * coisa. A seção Estudo fica aqui pelo mesmo motivo: acorde e escala não
 * pertencem a um instrumento, pertencem a quem toca qualquer um deles.
 *
 * Cordas e Baixo (que abre o Cordas já no baixo) entram na fase 8, com o Kit
 * do Cordas. Até lá os cards deles NÃO aparecem: card que leva a "em obra" é
 * pior que card nenhum.
 */
@Composable
fun InstrumentsHub(
    store: SettingsStore,
    destination: InstrumentDestination?,
    onDestinationChange: (InstrumentDestination?) -> Unit,
) {
    BackHandler(enabled = destination != null) { onDestinationChange(null) }

    val reduceMotion = rememberReduceMotion()
    AnimatedContent(
        targetState = destination,
        label = "instrument-destination",
        transitionSpec = {
            if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn() togetherWith fadeOut()
        },
    ) { dest ->
        when (dest) {
            null -> HubList(onOpen = onDestinationChange)
            InstrumentDestination.Piano -> PianoScreen(store)
            InstrumentDestination.Drums -> DrumsScreen(store)
            InstrumentDestination.Chords -> StudyScreen(store, StudyKind.Chords)
            InstrumentDestination.Scales -> StudyScreen(store, StudyKind.Scales)
        }
    }
}

@Composable
private fun HubList(onOpen: (InstrumentDestination) -> Unit) {
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
            Column(modifier = Modifier.widthIn(max = 620.dp)) {
                Text(
                    text = stringResource(R.string.cadentia_instruments_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textPrimary,
                    modifier = Modifier.padding(bottom = 14.dp, start = 2.dp),
                )
                for (card in InstrumentCard.playable) {
                    InstrumentCardView(
                        card = card,
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .testTag("instruments.${card.id}"),
                    ) { onOpen(card.destination) }
                }
                Text(
                    text = stringResource(R.string.cadentia_instruments_study).uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.9.sp,
                    color = CzTokens.textTertiary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 10.dp, start = 4.dp),
                )
                StudyCard(
                    titleRes = R.string.music_tabs_chords,
                    detailRes = R.string.cadentia_instruments_chords_detail,
                    icon = Icons.Filled.MusicNote,
                    tag = "instruments.chords",
                ) { onOpen(InstrumentDestination.Chords) }
                StudyCard(
                    titleRes = R.string.music_tabs_scales,
                    detailRes = R.string.cadentia_instruments_scales_detail,
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    tag = "instruments.scales",
                ) { onOpen(InstrumentDestination.Scales) }
            }
        }
    }
}

@Composable
private fun StudyCard(
    titleRes: Int,
    detailRes: Int,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(CzTokens.radiusMD)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(CzTokens.surface, shape)
            .border(1.dp, CzTokens.hairline, shape)
            .clickable(onClick = onClick)
            .padding(15.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .background(CzTokens.gold.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                .border(1.dp, CzTokens.gold.copy(alpha = 0.30f), RoundedCornerShape(12.dp)),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = CzTokens.gold, modifier = Modifier.size(19.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(titleRes),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            Text(
                text = stringResource(detailRes),
                fontSize = 12.5.sp,
                color = CzTokens.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CzTokens.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
