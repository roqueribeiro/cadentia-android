package com.levelhard.cadentia.features.more

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.BuildConfig
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.SampleBank
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens
import com.levelhard.cadentia.ui.PremiumBackground
import com.levelhard.cadentia.ui.WordmarkFamily
import com.levelhard.cadentia.ui.pageTransition

/**
 * A tela Sobre — port do `AboutView.swift`: o que é o Cadentia, como a
 * separação funciona, de onde ele veio e quem faz.
 *
 * Não é enfeite institucional: a loja exige que o app diga o que faz com os
 * dados de quem usa, e a licença do modelo embarcado exige que o aviso de
 * copyright viaje junto com o software. As duas obrigações moram aqui.
 */
@Composable
fun AboutScreen() {
    val accent = CzTokens.gold
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Box(Modifier.fillMaxSize().pageTransition()) {
        PremiumBackground(accent = accent)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                // Cabeçalho.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tab_tuner),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(34.dp),
                    )
                    Text(
                        text = "Cadentia", // i18n-verbatim: wordmark
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = WordmarkFamily,
                        color = CzTokens.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.cadentia_about_tagline),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = CzTokens.textSecondary,
                    )
                    Text(
                        text = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", // i18n-verbatim: versão
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CzTokens.textTertiary,
                        modifier = Modifier.testTag("about.version"),
                    )
                }

                Section(R.string.cadentia_about_what_title, R.string.cadentia_about_what_body)
                Section(R.string.cadentia_about_separation_title, R.string.cadentia_about_separation_body)
                Section(R.string.cadentia_about_privacy_title, R.string.cadentia_about_privacy_body)
                Section(R.string.cadentia_about_roqueos_title, R.string.cadentia_about_roqueos_body)

                // Quem faz. O crédito ao Rafael é específico de propósito: ele
                // não "colaborou", ele pediu o app e teve a ideia da separação.
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cadentia_about_makers_title),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = CzTokens.textPrimary,
                        )
                        Person(
                            name = "Roque Ribeiro", // i18n-verbatim: nome próprio
                            roleRes = R.string.cadentia_about_roque_role,
                            icon = Icons.Filled.Build,
                            accent = accent,
                            tag = "about.linkedin.roque",
                        ) { open("https://www.linkedin.com/in/roqueribeirosilva/") }
                        Divider(color = CzTokens.hairline)
                        Person(
                            name = "Rafael Luques", // i18n-verbatim: nome próprio
                            roleRes = R.string.cadentia_about_rafael_role,
                            icon = Icons.Filled.MusicNote,
                            accent = accent,
                            tag = "about.linkedin.rafael",
                        ) { open("https://www.linkedin.com/in/rafael-luques/") }
                    }
                }

                // Só endereços que existem de verdade: botão que leva a 404
                // numa tela Sobre é o detalhe que o revisor encontra.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinkRow(R.string.cadentia_about_link_site, Icons.Filled.Language, accent) {
                        open("https://roqueos.com.br")
                    }
                    LinkRow(R.string.cadentia_about_link_privacy, Icons.Filled.PanTool, accent) {
                        open("https://roqueos.com.br/privacy")
                    }
                    LinkRow(R.string.cadentia_about_link_terms, Icons.Filled.Description, accent) {
                        open("https://roqueos.com.br/terms")
                    }
                    LinkRow(R.string.cadentia_about_link_community, Icons.Filled.Forum, accent) {
                        open("https://roqueos.com.br/community")
                    }
                    LinkRow(R.string.cadentia_about_link_licenses, Icons.Filled.Verified, accent) {
                        showLicenses = true
                    }
                }
            }
        }
    }

    if (showLicenses) {
        LicensesSheet(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun Section(titleRes: Int, bodyRes: Int) {
    CzCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = CzTokens.textPrimary,
            )
            Text(
                text = stringResource(bodyRes),
                fontSize = 14.sp,
                color = CzTokens.textSecondary,
            )
        }
    }
}

/** A linha inteira é o alvo, não só o texto "LinkedIn". */
@Composable
private fun Person(
    name: String,
    roleRes: Int,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = name, onClick = onClick)
            .testTag(tag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .width(26.dp)
                .size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            Text(
                text = stringResource(roleRes),
                fontSize = 13.sp,
                color = CzTokens.textSecondary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = "LinkedIn", // i18n-verbatim: marca
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LinkRow(
    titleRes: Int,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(CzTokens.surface, RoundedCornerShape(CzTokens.radiusMD))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = stringResource(titleRes),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = CzTokens.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CzTokens.textTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Licenças de terceiros — OBRIGAÇÃO LEGAL, não cortesia: o app embarca o
 * htdemucs (Meta, MIT), e a MIT exige que o aviso de copyright acompanhe
 * toda cópia. O texto fica em inglês de propósito: é o original, e traduzir
 * licença muda o que ela diz.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.cadentia_about_link_licenses),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            Text(
                text = stringResource(R.string.cadentia_about_licenses_intro),
                fontSize = 13.sp,
                color = CzTokens.textSecondary,
            )
            CzCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "Demucs (htdemucs)", // i18n-verbatim: nome do projeto
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = CzTokens.textPrimary,
                    )
                    Text(
                        text = "github.com/adefossez/demucs", // i18n-verbatim: endereço
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CzTokens.textTertiary,
                    )
                    Text(
                        text = MIT_LICENSE, // i18n-verbatim: texto legal original
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CzTokens.textSecondary,
                    )
                }
            }
            // O modo câmera do Cordas roda o Hand Landmarker do MediaPipe. O
            // iOS usa o Vision e não deve nada a ninguém; aqui a Apache-2.0
            // pede o aviso — e ele fica onde a pessoa pode ler.
            CzCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "MediaPipe Tasks (Hand Landmarker)", // i18n-verbatim: nome do projeto
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = CzTokens.textPrimary,
                    )
                    Text(
                        text = "github.com/google-ai-edge/mediapipe", // i18n-verbatim: endereço
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CzTokens.textTertiary,
                    )
                    Text(
                        text = APACHE_NOTICE, // i18n-verbatim: texto legal original
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CzTokens.textSecondary,
                    )
                }
            }
            // Os bancos de sample são obra de outras pessoas: mesma pergunta
            // ("de onde veio esse som?"), mesma seção da folha de Som.
            SampleCreditsSection(remember { SampleBank.shared.installed })
        }
    }
}

/** O aviso da Apache-2.0 como o MediaPipe publica, sem alteração. */
private const val APACHE_NOTICE = """Copyright 2019 The MediaPipe Authors.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License."""

/** Texto da MIT como publicado no repositório do Demucs, sem alteração. */
private const val MIT_LICENSE = """MIT License

Copyright (c) Meta Platforms, Inc. and affiliates.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""
