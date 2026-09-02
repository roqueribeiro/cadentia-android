package com.levelhard.cadentia.features.tab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.levelhard.cadentia.I18nMap
import com.levelhard.cadentia.R
import com.levelhard.cadentia.kit.BackingTrack
import com.levelhard.cadentia.kit.Chord
import com.levelhard.cadentia.kit.ChordLibrary
import com.levelhard.cadentia.kit.DrumSynth
import com.levelhard.cadentia.kit.InstrumentVoice
import com.levelhard.cadentia.kit.Tablature
import com.levelhard.cadentia.ui.CzCard
import com.levelhard.cadentia.ui.CzTokens

/** Chip de cápsula compartilhado pelas sheets da tablatura. */
@Composable
private fun SheetChip(
    text: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        color = if (active) accent else CzTokens.textSecondary,
        modifier = Modifier
            .background(
                if (active) accent.copy(alpha = 0.18f) else CzTokens.surface,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}

// ---- Mixer ----

/** Port do `TabMixerSheet`: mute/solo/volume + voz ou kit por trilha. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabMixerSheet(
    tab: Tablature,
    accent: Color,
    revision: Int,
    onDismiss: () -> Unit,
    onChange: (index: Int, volume: Double, muted: Boolean, soloed: Boolean) -> Unit,
    onVoice: (index: Int, voiceId: String) -> Unit,
    onKit: (index: Int, kitId: String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        @Suppress("UNUSED_EXPRESSION") revision
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.tablature_tracks_mixer),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            for ((index, track) in tab.tracks.withIndex()) {
                CzCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = track.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = CzTokens.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            ToggleChip(
                                text = stringResource(R.string.tablature_tracks_mute),
                                active = track.muted,
                                color = CzTokens.danger,
                            ) { onChange(index, track.volume, !track.muted, track.soloed) }
                            Spacer(Modifier.size(8.dp))
                            ToggleChip(
                                text = stringResource(R.string.tablature_tracks_solo),
                                active = track.soloed,
                                color = CzTokens.gold,
                            ) { onChange(index, track.volume, track.muted, !track.soloed) }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.tablature_tracks_volume),
                                tint = CzTokens.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Slider(
                                value = track.volume.toFloat(),
                                onValueChange = { onChange(index, it.toDouble(), track.muted, track.soloed) },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = accent,
                                    activeTrackColor = accent,
                                    inactiveTrackColor = CzTokens.surface,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        VoicePicker(index = index, track = track, accent = accent, onVoice = onVoice, onKit = onKit)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(text: String, active: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        color = if (active) Color.Black else CzTokens.textSecondary,
        modifier = Modifier
            .background(if (active) color else CzTokens.surface, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * O elenco inteiro que o motor sintetiza, por tipo de trilha, direto do
 * registro compartilhado (`InstrumentVoice.forTrackType`); bateria troca de
 * kit.
 */
@Composable
private fun VoicePicker(
    index: Int,
    track: Tablature.Track,
    accent: Color,
    onVoice: (Int, String) -> Unit,
    onKit: (Int, String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val labelKey = if (track.type == "drums") {
        DrumSynth.kitNameKey(track.kitId ?: "acoustic")
    } else {
        val options = InstrumentVoice.forTrackType(track.type)
        (InstrumentVoice.from(track.voiceId) ?: options.firstOrNull() ?: InstrumentVoice.GuitarClean).nameKey
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .background(CzTokens.surface, RoundedCornerShape(50))
            .clickable { open = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(I18nMap.res(labelKey)),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = CzTokens.textSecondary,
        )
        Icon(
            imageVector = Icons.Filled.UnfoldMore,
            contentDescription = null,
            tint = CzTokens.textTertiary,
            modifier = Modifier.size(12.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (track.type == "drums") {
                for (kit in DrumSynth.kitIDs) {
                    DropdownMenuItem(
                        text = { Text(stringResource(I18nMap.res(DrumSynth.kitNameKey(kit)))) },
                        leadingIcon = {
                            if (track.kitId == kit) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                        onClick = {
                            onKit(index, kit)
                            open = false
                        },
                    )
                }
            } else {
                for (voice in InstrumentVoice.forTrackType(track.type)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(I18nMap.res(voice.nameKey))) },
                        leadingIcon = {
                            if (track.voiceId == voice.id) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                        onClick = {
                            onVoice(index, voice.id)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

// ---- Catálogo de bases ----

/**
 * Port do `BackingTrackCatalogSheet`: escolha um groove por gênero e receba
 * uma tablatura pronta para tocar (progressão + bateria).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackingTrackCatalogSheet(
    accent: Color,
    onDismiss: () -> Unit,
    onPick: (BackingTrack, String) -> Unit,
) {
    var genre by remember { mutableStateOf("rock") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.tablature_backing_tracks_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                for (candidate in BackingTrack.genres) {
                    SheetChip(
                        text = stringResource(I18nMap.res("tablature.backingTracks.genres.$candidate")),
                        active = genre == candidate,
                        accent = accent,
                    ) { genre = candidate }
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                for (template in BackingTrack.byGenre(genre)) {
                    BackingTrackRow(template = template, accent = accent, onPick = onPick)
                }
            }
        }
    }
}

@Composable
private fun BackingTrackRow(
    template: BackingTrack,
    accent: Color,
    onPick: (BackingTrack, String) -> Unit,
) {
    val title = stringResource(I18nMap.res(template.nameKey))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(CzTokens.surface, RoundedCornerShape(CzTokens.radiusMD))
            .clickable { onPick(template, title) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            Text(
                text = template.chordProgression.take(4).joinToString(" · "),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = accent.copy(alpha = 0.85f),
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${template.bpm} " + stringResource(R.string.tablature_bpm),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textTertiary,
            )
            Text(
                text = "${template.measureCount}×",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = CzTokens.textTertiary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CzTokens.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ---- Escolha de acorde ----

/** Port do `ChordPickerSheet`: fundamental × qualidade, prévia e inserir. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChordPickerSheet(
    accent: Color,
    onDismiss: () -> Unit,
    onPick: (Chord) -> Unit,
) {
    var root by remember { mutableStateOf("C") }
    var quality by remember { mutableStateOf("maj") }
    val chord = ChordLibrary.find(root, quality)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CzTokens.stageTop) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(16.dp)
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.tablature_insert_chord),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = CzTokens.textPrimary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (candidate in ChordLibrary.roots) {
                    SheetChip(text = candidate, active = root == candidate, accent = accent) {
                        root = candidate
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                for (candidate in ChordLibrary.qualities) {
                    SheetChip(text = candidate.label, active = quality == candidate.id, accent = accent) {
                        quality = candidate.id
                    }
                }
            }
            if (chord != null) {
                Text(
                    text = chord.displayName,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = CzTokens.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = chord.notes.joinToString(" · "),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(accent, RoundedCornerShape(50))
                        .clickable { onPick(chord) }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.tablature_insert_chord),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
