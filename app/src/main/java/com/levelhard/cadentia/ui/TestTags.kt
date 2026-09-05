package com.levelhard.cadentia.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Os `testTag` desta subárvore viram `resource-id` na árvore de
 * acessibilidade, para o androidTest (uiautomator) achar `stems.play` e
 * companhia. A raiz do app já faz isto; toda folha modal (`ModalBottomSheet`)
 * é OUTRA janela e precisa fazer de novo na raiz do conteúdo dela.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }
