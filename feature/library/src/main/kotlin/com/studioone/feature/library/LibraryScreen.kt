package com.studioone.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.component.StudioBadge
import com.studioone.core.designsystem.component.StudioCard
import com.studioone.core.designsystem.component.StudioEmptyState
import com.studioone.core.designsystem.component.StudioTextButton
import com.studioone.core.domain.model.library.LoopItem

/** Loop / sample browser: search, preview, favorite, import, drag-in-ready. */
@Composable
fun LibraryScreen(
    onAddToProject: (LoopItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else "sample.wav"
            } ?: "sample.wav"
            viewModel.importUri(uri.toString(), name)
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.addToProjectEvents.collect(onAddToProject)
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = state.filter.query,
            onValueChange = viewModel::search,
            placeholder = { Text(stringResource(R.string.library_search)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            StudioTextButton(
                text = stringResource(R.string.library_import),
                onClick = { importLauncher.launch(arrayOf("audio/*")) },
            )
        }

        if (state.items.isEmpty() && !state.isLoading) {
            StudioEmptyState(
                title = stringResource(R.string.library_title),
                subtitle = stringResource(R.string.library_empty),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items, key = { it.id }) { item ->
                    LibraryRow(
                        item = item,
                        previewing = state.previewingId == item.id,
                        onPreview = { viewModel.preview(item) },
                        onFavorite = { viewModel.toggleFavorite(item) },
                        onAdd = { viewModel.addToProject(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    item: LoopItem,
    previewing: Boolean,
    onPreview: () -> Unit,
    onFavorite: () -> Unit,
    onAdd: () -> Unit,
) {
    StudioCard {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudioBadge(item.kind.name.replace("_", " ").lowercase())
                    item.bpm?.let { StudioBadge("%.0f BPM".format(it)) }
                    item.key?.let { StudioBadge(it.display) }
                }
            }
            IconButton(onClick = onPreview) {
                Icon(
                    if (previewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.library_preview),
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (item.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            StudioTextButton(text = stringResource(R.string.library_add_to_project), onClick = onAdd)
        }
    }
}
