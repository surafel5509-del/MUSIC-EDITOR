package com.studioone.mobile.feature.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.S1PrimaryButton
import com.studioone.mobile.core.designsystem.components.S1SecondaryButton
import com.studioone.mobile.core.model.AudioMetadata
import com.studioone.mobile.core.model.ExportFormat
import com.studioone.mobile.core.model.ExportKind
import com.studioone.mobile.core.model.ExportPhase

/**
 * Export flow sheet: kind -> format/quality -> range/loudness -> metadata ->
 * progress -> share. Entitlement failures surface inline with an upgrade CTA
 * (paywall deep link), never as a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onDismiss: () -> Unit,
    onShare: (uri: String) -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).padding(bottom = 40.dp),
        ) {
            Text("Export ${state.projectName}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Text("What", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportKind.entries.take(4).forEach { kind ->
                    FilterChip(selected = settings.kind == kind, onClick = { viewModel.setKind(kind) },
                        label = { Text(kind.displayName, fontSize = 11.sp) })
                }
            }
            if (settings.kind == ExportKind.STEMS) {
                Spacer(Modifier.height(8.dp))
                Text("Select stems", style = MaterialTheme.typography.labelSmall)
                state.availableTrackIds.forEach { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = id in state.stemTrackIds, onCheckedChange = { viewModel.toggleStemTrack(id) })
                        Text(name, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Format", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = settings.format == format,
                        onClick = { viewModel.setFormat(format) },
                        label = { Text(format.displayName + if (format.premiumOnly) " ★" else "", fontSize = 11.sp) },
                    )
                }
            }

            if (settings.format == ExportFormat.MP3 || settings.format == ExportFormat.AAC) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bitrate: ${settings.bitrateKbps} kbps", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = settings.bitrateKbps.toFloat(),
                        onValueChange = { viewModel.setBitrate((it / 32).toInt() * 32) },
                        valueRange = 96f..320f, steps = 6,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sample rate", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                listOf(44100, 48000, 96000).forEach { rate ->
                    FilterChip(selected = settings.sampleRate == rate, onClick = { viewModel.setSampleRate(rate) },
                        label = { Text("${rate / 1000}k", fontSize = 10.sp) },
                        modifier = Modifier.padding(start = 4.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Normalize peak", style = MaterialTheme.typography.bodyMedium)
                    Text("to ${settings.targetPeakDb} dBFS", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.normalize, onCheckedChange = viewModel::setNormalize)
            }

            Spacer(Modifier.height(8.dp))
            Text("Streaming loudness target", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "Off", -14f to "-14 LUFS", -16f to "-16 LUFS", -9f to "-9 LUFS").forEach { (lufs, label) ->
                    FilterChip(
                        selected = settings.loudnessTargetLUFS == lufs,
                        onClick = { viewModel.setLoudnessTarget(lufs) },
                        label = { Text(label, fontSize = 10.sp) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Metadata", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = settings.metadata.title ?: "",
                onValueChange = { viewModel.setMetadata(settings.metadata.copy(title = it)) },
                label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = settings.metadata.artist ?: "",
                onValueChange = { viewModel.setMetadata(settings.metadata.copy(artist = it)) },
                label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = settings.metadata.genre ?: "",
                onValueChange = { viewModel.setMetadata(settings.metadata.copy(genre = it)) },
                label = { Text("Genre") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            state.entitlementError?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                S1SecondaryButton("See Pro plans", onUpgrade)
            }

            Spacer(Modifier.height(16.dp))
            state.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when (progress.phase) {
                        ExportPhase.PREPARING -> "Preparing render…"
                        ExportPhase.RENDERING -> "Rendering mixdown${progress.currentStem?.let { " — $it" } ?: ""}…"
                        ExportPhase.ENCODING -> "Encoding ${settings.format.displayName}…"
                        ExportPhase.WRITING_METADATA -> "Writing metadata…"
                        ExportPhase.COMPLETE -> "Done — saved to Music/StudioOne"
                        ExportPhase.FAILED -> "Export failed: ${progress.error}"
                    },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            if (state.outputUri != null) {
                S1PrimaryButton("Share", onClick = { onShare(state.outputUri!!) }, modifier = Modifier.fillMaxWidth())
            } else {
                S1PrimaryButton(
                    text = if (state.isExporting) "Exporting…" else "Export",
                    onClick = { if (state.isExporting) viewModel.cancelExport() else viewModel.startExport() },
                    enabled = !state.isExporting || state.progress != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
