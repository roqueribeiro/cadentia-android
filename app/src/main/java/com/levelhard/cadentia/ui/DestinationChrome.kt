package com.levelhard.cadentia.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R

/**
 * A barra de navegação inline que o `NavigationStack` do iOS dá de graça a
 * cada destino empurrado (`.navigationTitle` + `.inline`): voltar e o
 * título. Serve ao hub de Instrumentos (Piano, Bateria, Acordes, Escalas) e
 * aos destinos do Mais (Gravador, Frequência, Sobre; a Tablatura tem título
 * próprio no cabeçalho e passa só o voltar, como no iOS). Sem ela os
 * destinos abriam sem dizer onde a pessoa está e só o gesto do sistema
 * voltava (auditoria de 04/09; comparação tela a tela de 05/09).
 *
 * `title == null` desenha só o voltar, na mesma altura, para o conteúdo
 * abaixo não pular entre destinos com e sem título.
 */
@Composable
fun DestinationChrome(
    title: String?,
    accent: Color,
    onBack: () -> Unit,
    backTag: String = "destination.back",
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PremiumBackground(accent = accent)
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag(backTag)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = accent,
                    )
                }
                Text(
                    text = title ?: "",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // Espelho do botão de voltar, para o título ficar centrado
                // como no iOS.
                Spacer(Modifier.size(48.dp))
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
