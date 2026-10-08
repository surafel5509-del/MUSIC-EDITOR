package com.studioone.mobile.feature.projects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.EmptyState
import com.studioone.mobile.core.designsystem.components.S1PrimaryButton
import com.studioone.mobile.core.designsystem.components.StatusChip
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.SyncStatus
import kotlin.math.roundToInt

/**
 * Project browser: recents/favorites/archive/cloud tabs, search, grid of
 * project cards with sync status, and the new-project sheet (template +
 * name + BPM). Long-press opens quick actions; overflow menu has the rest.
 * All strings are resource-backed for localization.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateSheet by remember { mutableStateOf(false) }
    var contextMenuProject by remember { mutableStateOf<Project?>(null) }

    // Consume one-shot navigation events.
    LaunchedEffect(state.createdProjectId, state.openedProjectId) {
        state.createdProjectId?.let { onOpenProject(it); viewModel.consumeOneShots() }
        state.openedProjectId?.let { onOpenProject(it); viewModel.consumeOneShots() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.CloudSync, contentDescription = stringResource(R.string.projects_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.projects_new))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Search field.
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onIntent(ProjectsIntent.Search(it)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.projects_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = S1Shapes.medium,
            )
            // Tabs.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProjectsTab.entries.forEach { t ->
                    FilterChip(
                        selected = state.tab == t,
                        onClick = { viewModel.onIntent(ProjectsIntent.SetTab(t)) },
                        label = { Text(t.displayName()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (state.projects.isEmpty() && !state.isLoading) {
                EmptyState(
                    title = stringResource(R.string.projects_empty_title),
                    body = stringResource(R.string.projects_empty_body),
                    icon = Icons.Default.MusicNote,
                    actionText = stringResource(R.string.projects_new),
                    onAction = { showCreateSheet = true },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 170.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.projects, key = { it.id.value }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { viewModel.onIntent(ProjectsIntent.Open(project.id)) },
                            onLongClick = { contextMenuProject = project },
                            onFavorite = { viewModel.onIntent(ProjectsIntent.ToggleFavorite(project.id)) },
                            onMore = { contextMenuProject = project },
                        )
                    }
                }
            }
        }
    }

    // Context actions sheet.
    contextMenuProject?.let { project ->
        ModalBottomSheet(onDismissRequest = { contextMenuProject = null }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                ProjectAction(stringResource(R.string.projects_open)) {
                    viewModel.onIntent(ProjectsIntent.Open(project.id)); contextMenuProject = null
                }
                ProjectAction(stringResource(R.string.projects_duplicate)) {
                    viewModel.onIntent(ProjectsIntent.Duplicate(project.id)); contextMenuProject = null
                }
                ProjectAction(
                    if (project.isArchived) stringResource(R.string.projects_unarchive)
                    else stringResource(R.string.projects_archive),
                ) {
                    viewModel.onIntent(ProjectsIntent.Archive(project.id, !project.isArchived)); contextMenuProject = null
                }
                ProjectAction(stringResource(R.string.projects_delete), destructive = true) {
                    viewModel.onIntent(ProjectsIntent.Delete(project.id)); contextMenuProject = null
                }
            }
        }
    }

    // Entitlement limit -> paywall.
    state.limitMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeOneShots() },
            title = { Text(stringResource(R.string.projects_limit_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { onUpgradeClick(); viewModel.consumeOneShots() }) {
                    Text(stringResource(R.string.projects_upgrade))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.consumeOneShots() }) { Text(stringResource(R.string.projects_not_now)) }
            },
        )
    }

    if (showCreateSheet) {
        NewProjectSheet(
            onDismiss = { showCreateSheet = false },
            onCreate = { name, template, bpm ->
                viewModel.onIntent(ProjectsIntent.Create(name, template, bpm))
                showCreateSheet = false
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Project ${project.name}" }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = S1Shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.aspectRatio(1.35f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            // Artwork or generated gradient from project key/tempo.
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    StatusChip(
                        text = "${project.tempoMap.baseBpm.roundToInt()} BPM",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        if (project.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.projects_favorite),
                        tint = if (project.isFavorite) S1Colors.StudioMagenta else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp).combinedClickable(onClick = onFavorite, onLongClick = null),
                    )
                }
                Column {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${project.tracks.size} tracks · ${project.key}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Sync badge.
            SyncBadge(project.syncStatus, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

@Composable
private fun SyncBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val (icon, tint, desc) = when (status) {
        SyncStatus.SYNCED -> Triple(Icons.Default.CloudDone, S1Colors.MeterGreen, "Synced")
        SyncStatus.PENDING_UPLOAD -> Triple(Icons.Default.CloudUpload, S1Colors.SunsetAmber, "Uploading")
        SyncStatus.PENDING_DOWNLOAD -> Triple(Icons.Default.CloudSync, S1Colors.SunsetAmber, "Downloading")
        SyncStatus.CONFLICT -> Triple(Icons.Default.CloudOff, S1Colors.RecordRed, "Conflict — review needed")
        SyncStatus.ERROR -> Triple(Icons.Default.CloudOff, S1Colors.RecordRed, "Sync error")
        SyncStatus.LOCAL_ONLY -> Triple(Icons.Default.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant, "On this device only")
    }
    Icon(icon, contentDescription = desc, tint = tint, modifier = modifier.size(16.dp))
}

@Composable
private fun ProjectAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectSheet(
    onDismiss: () -> Unit,
    onCreate: (name: String, template: ProjectTemplate, bpm: Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(ProjectTemplate.BEAT) }
    var bpm by remember { mutableStateOf(120f) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 40.dp)) {
            Text(stringResource(R.string.projects_new), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.projects_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.projects_template_label), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // First row of templates; full list scrolls in the sheet.
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectTemplate.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            FilterChip(
                                selected = template == t,
                                onClick = { template = t },
                                label = { Text(t.displayName()) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${stringResource(R.string.projects_bpm_label)}: ${bpm.roundToInt()}",
                    style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = bpm, onValueChange = { bpm = it },
                    valueRange = 60f..200f,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            S1PrimaryButton(
                text = stringResource(R.string.projects_create),
                onClick = { onCreate(name.ifBlank { "Untitled" }, template, bpm.toDouble()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun ProjectsTab.displayName(): String = when (this) {
    ProjectsTab.RECENTS -> "Recents"
    ProjectsTab.FAVORITES -> "Favorites"
    ProjectsTab.ARCHIVE -> "Archive"
    ProjectsTab.CLOUD -> "Cloud"
}

private fun ProjectTemplate.displayName(): String = when (this) {
    ProjectTemplate.EMPTY -> "Empty"
    ProjectTemplate.BEAT -> "Beat"
    ProjectTemplate.SONG -> "Song"
    ProjectTemplate.PODCAST -> "Podcast"
    ProjectTemplate.LIVE_RECORDING -> "Live"
    ProjectTemplate.REMIX -> "Remix"
    ProjectTemplate.VOCAL_SESSION -> "Vocals"
}
