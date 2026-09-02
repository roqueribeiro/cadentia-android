package com.levelhard.cadentia.features.tuner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.I18nMap
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.InstrumentPreset
import com.levelhard.cadentia.kit.TuningCatalog
import com.levelhard.cadentia.kit.TuningRow
import com.levelhard.cadentia.ui.CzTokens

/**
 * As 49 linhas do catálogo já traduzidas, uma vez por idioma — o
 * `TuningRows.all` do iOS. O corpo do afinador recompõe a cada leitura do
 * microfone; traduzir 98 chaves por leitura seria desperdício.
 */
@Composable
fun rememberTuningRows(): List<TuningRow> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration.locales) {
        TuningCatalog.rows { key -> context.getString(I18nMap.res(key)) }
    }
}

/**
 * O seletor de afinações — port do `TuningPickerSheet`. Era um menu de oito
 * itens; com 49, virou folha: busca no topo, seções na ordem do catálogo e as
 * últimas usadas fixadas antes de tudo. Cada linha mostra as notas (muita
 * gente sabe a forma e não o nome) e quem tornou a afinação conhecida
 * (ninguém busca "Open G", busca "aquela do Keith Richards").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningPickerSheet(
    rows: List<TuningRow>,
    selectedId: String,
    recentIds: List<String>,
    onPick: (InstrumentPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val visible = TuningCatalog.filter(rows, query)
    val recents = TuningCatalog.recentRows(rows, recentIds)
    val sections = TuningCatalog.sections(visible) { key -> context.getString(I18nMap.res(key)) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(Modifier.fillMaxWidth().heightIn(min = 480.dp)) {
            Text(
                text = stringResource(R.string.cadentia_tuner_tunings_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = CzTokens.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.cadentia_tuner_tunings_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CzTokens.textPrimary,
                    unfocusedTextColor = CzTokens.textPrimary,
                    focusedBorderColor = CzTokens.tunerGreen,
                    unfocusedBorderColor = CzTokens.hairline,
                    focusedLabelColor = CzTokens.tunerGreen,
                    unfocusedLabelColor = CzTokens.textTertiary,
                    cursorColor = CzTokens.tunerGreen,
                    focusedLeadingIconColor = CzTokens.textSecondary,
                    unfocusedLeadingIconColor = CzTokens.textTertiary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("tuning.search"),
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!searching && recents.isNotEmpty()) {
                    item(key = "header/recent") { SectionHeader(stringResource(R.string.cadentia_tuner_tunings_recent)) }
                    // Chave PRÓPRIA para a recente: a mesma afinação existe de novo
                    // lá embaixo na seção dela, e duas chaves iguais no mesmo
                    // contêiner preguiçoso é a linha que some (lição do iOS).
                    items(recents, key = { "recent/${it.id}" }) { row ->
                        TuningRowView(row, row.id == selectedId, tag = "tuning.recent.${row.id}") {
                            onPick(row.preset)
                            onDismiss()
                        }
                    }
                }
                for (section in sections) {
                    // O cromático é uma linha só e o cabeçalho repetiria o nome dela.
                    if (section.group != InstrumentPreset.Group.chromatic) {
                        item(key = "header/${section.id}") { SectionHeader(section.title) }
                    }
                    items(section.rows, key = { "row/${it.id}" }) { row ->
                        TuningRowView(row, row.id == selectedId, tag = "tuning.row.${row.id}") {
                            onPick(row.preset)
                            onDismiss()
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "empty") { EmptyState() }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        color = CzTokens.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .padding(horizontal = 4.dp),
    )
}

@Composable
private fun TuningRowView(item: TuningRow, isSelected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(CzTokens.radiusMD)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) CzTokens.tunerGreen.copy(alpha = 0.12f) else CzTokens.surface, shape)
            .border(1.dp, if (isSelected) CzTokens.tunerGreen.copy(alpha = 0.45f) else CzTokens.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag(tag),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = item.title, // i18n-verbatim: já traduzido ou nome próprio
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (item.notes.isEmpty()) {
                    // Cromático: o que informa é o que ele faz.
                    Text(
                        text = stringResource(R.string.music_tuner_tunings_any_note),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = CzTokens.textSecondary,
                    )
                } else {
                    Text(
                        text = item.family, // i18n-verbatim: já traduzido
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = CzTokens.textSecondary,
                        maxLines = 1,
                    )
                    // As notas em dourado monoespaçado: é o que o olho procura
                    // primeiro quando se sabe a forma e não o nome.
                    Text(
                        text = item.notes, // i18n-verbatim: nomes de nota
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = CzTokens.gold.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.artists.isNotEmpty()) {
                Text(
                    text = item.artists, // i18n-verbatim: nomes de banda
                    fontSize = 11.sp,
                    color = CzTokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = CzTokens.tunerGreen,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = CzTokens.textTertiary,
            modifier = Modifier.size(30.dp),
        )
        Text(
            text = stringResource(R.string.cadentia_tuner_tunings_empty),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = CzTokens.textSecondary,
        )
        Text(
            text = stringResource(R.string.cadentia_tuner_tunings_empty_hint),
            fontSize = 12.sp,
            color = CzTokens.textTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
    }
}
