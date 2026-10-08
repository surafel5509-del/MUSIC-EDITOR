package com.studioone.mobile.feature.loops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.LibraryCategory
import com.studioone.mobile.core.model.LibraryGenre
import com.studioone.mobile.core.model.LibraryItem

/**
 * Loop/sample browser. Preview playback is Media3-driven from the ViewModel's
 * previewingItemId (wired via the app-level player); drop-into-timeline uses
 * the shared DragAndDrop payload (see core:ui DragPayload) consumed by the
 * arranger — the browser stays decoupled from the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopsScreen(
    onBack: () -> Unit,
    onPreview: (LibraryItem) -> Unit,
    onStopPreview: () -> Unit,
    onAddToProject: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoopsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Sound Library") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search loops, one-shots, MIDI packs…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = S1Shapes.medium,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryCategory.entries.toList()) { cat ->
                FilterChip(selected = state.category == cat,
                    onClick = { viewModel.setCategory(cat) },
                    label = { Text(cat.displayName, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = state.genre == null, onClick = { viewModel.setGenre(null) },
                    label = { Text("All genres", fontSize = 11.sp) })
            }
            items(LibraryGenre.entries.toList()) { g ->
                FilterChip(selected = state.genre == g, onClick = { viewModel.setGenre(g) },
                    label = { Text(g.displayName, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items, key = { it.id.value }) { item ->
                LibraryRow(
                    item = item,
                    isPreviewing = state.previewingItemId == item.id.value,
                    downloadProgress = state.downloads[item.id.value],
                    onPreview = {
                        if (state.previewingItemId == item.id.value) { onStopPreview(); viewModel.setPreviewing(null) }
                        else { onPreview(item); viewModel.setPreviewing(item.id.value) }
                    },
                    onDownload = { viewModel.download(item) },
                    onAdd = { onAddToProject(item) },
                )
            }
            if (state.isLoading) {
                item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                } }
            }
            if (state.cursor != null && !state.isLoading) {
                item { Text("Load more…", modifier = Modifier.fillMaxWidth()
                    .clickable { viewModel.loadMore() }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    item: LibraryItem,
    isPreviewing: Boolean,
    downloadProgress: Float?,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onAdd: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = S1Shapes.small,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPreview) {
                Icon(
                    if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPreviewing) "Stop preview" else "Preview ${item.name}",
                    tint = if (isPreviewing) S1Colors.RecordRed else MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.bpm?.let { Text("${it.toInt()} BPM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item.key?.let { Text(it.toString(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(item.category.displayName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.isPremium) {
                        Text("PRO", fontSize = 9.sp, color = S1Colors.SunsetAmber)
                    }
                }
                if (downloadProgress != null && downloadProgress < 1f) {
                    LinearProgressIndicator(progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (!item.isDownloaded && downloadProgress == null) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.CloudDownload, "Download ${item.name}")
                }
            } else {
                Icon(Icons.Default.DragIndicator, "Drag into timeline",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
