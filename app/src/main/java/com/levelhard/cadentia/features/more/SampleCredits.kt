package com.levelhard.cadentia.features.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.SamplePack
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens

/**
 * Os créditos dos bancos de sample — port do `SampleCredits.swift`.
 *
 * Aparece nas licenças de terceiros e na tela de som, com o mesmo conteúdo,
 * porque as duas perguntas são a mesma: "de onde veio esse som?". Nenhum dos
 * bancos exige atribuição — CC0 dispensa — e é justamente por isso que
 * creditar é decisão nossa: quem publica em domínio público merece o nome no
 * produto que se beneficiou disso. Sem pack instalado a seção não existe.
 */
@Composable
fun SampleCreditsSection(packs: List<SamplePack>, modifier: Modifier = Modifier) {
    if (packs.isEmpty()) return
    CzCard(modifier = modifier.fillMaxWidth().testTag("credits.samples")) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.cadentia_sound_credits_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = CzTokens.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.cadentia_sound_credits_intro),
                fontSize = 11.sp,
                color = CzTokens.textSecondary,
            )
            for (pack in packs) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        text = pack.name, // i18n-verbatim: nome do banco, dado do manifesto
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CzTokens.textPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pack.license, // i18n-verbatim: identificador SPDX
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CzTokens.tunerGreen,
                        )
                        Text(
                            text = host(pack.source), // i18n-verbatim: endereço
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CzTokens.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** O endereço sem o `https://`, que ninguém lê e ocupa a largura toda. */
internal fun host(url: String): String = url.removePrefix("https://").removePrefix("http://")
