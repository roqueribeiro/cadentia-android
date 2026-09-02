package com.levelhard.cadentia.features.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzTokens

/**
 * Som dos instrumentos — port da `SoundSettingsView.swift` (1.16): onde se
 * escolhe, por família de instrumento, entre síntese e sample.
 *
 * As linhas por família (interruptor, origem e licença de cada banco) chegam
 * com o `SampleBank` na fase 7. Até lá esta folha mostra o mesmo estado que o
 * iOS mostra quando o build foi montado sem os bancos: nenhum instalado, tudo
 * tocando com a síntese do app. É a verdade deste build, não um "em obra".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .testTag("sound.sheet"),
        ) {
            Text(
                text = stringResource(R.string.cadentia_sound_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = CzTokens.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
            Text(
                text = stringResource(R.string.cadentia_sound_hint),
                fontSize = 13.sp,
                color = CzTokens.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SoundEmptyState()
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
