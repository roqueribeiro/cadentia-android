package com.levelhard.cadentia.features.cordas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R
import com.levelhard.cadentia.ui.CzTokens

/**
 * O que fazer com a tela, na primeira vez que cada jeito de tocar aparece —
 * port do `CordasCoach.swift` (1.16).
 *
 * Nada num braço desenhado diz que a metade de cima aperta e a de baixo bate.
 * O founder abriu a tela e teve que ouvir a explicação em voz alta — ou seja,
 * "óbvio depois que se sabe" estava fazendo o trabalho, e isso não é design.
 * Aparece uma vez por modo, e a interrogação da barra traz de volta.
 */
@Composable
fun CordasCoach(mode: CordasModel.Mode, onDismiss: () -> Unit) {
    val (icon, titleRes, bodyRes) = when (mode) {
        CordasModel.Mode.Frets -> Triple(Icons.Filled.TouchApp, R.string.cadentia_cordas_coach_frets_title, R.string.cadentia_cordas_coach_frets_body)
        CordasModel.Mode.Chords -> Triple(Icons.Filled.GridView, R.string.cadentia_cordas_coach_chords_title, R.string.cadentia_cordas_coach_chords_body)
        CordasModel.Mode.Camera -> Triple(Icons.Filled.BackHand, R.string.cadentia_cordas_coach_camera_title, R.string.cadentia_cordas_coach_camera_body)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.66f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics { }
            .testTag("cordas.coach"),
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp),
            modifier = Modifier
                .padding(26.dp)
                .widthIn(max = 320.dp)
                .background(Color(0.07f, 0.07f, 0.09f), shape)
                .border(1.5.dp, CzTokens.gold.copy(alpha = 0.35f), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(22.dp),
        ) {
            Icon(icon, contentDescription = null, tint = CzTokens.gold, modifier = Modifier.size(30.dp))
            Text(
                text = stringResource(titleRes),
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                color = CzTokens.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(bodyRes),
                fontSize = 13.sp,
                color = CzTokens.textSecondary,
                textAlign = TextAlign.Center,
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .fillMaxWidth()
                    .background(CzTokens.gold, RoundedCornerShape(12.dp))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 11.dp)
                    .testTag("cordas.coach.got"),
            ) {
                Text(
                    text = stringResource(R.string.cadentia_cordas_coach_got),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
            }
        }
    }
}
