package com.studioone.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.component.StudioBadge
import com.studioone.core.designsystem.component.StudioCard
import com.studioone.core.designsystem.component.StudioEmptyState
import com.studioone.core.designsystem.component.StudioLoadingView
import com.studioone.core.designsystem.component.StudioPrimaryButton
import com.studioone.core.designsystem.theme.trackColor
import com.studioone.core.domain.model.Project

/** Project list: search, filter, grid of cards, FAB into the template picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onOpenProject: (projectId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { projectId -> onOpenProject(projectId) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.home_title)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.onAction(HomeAction.OpenProjectCreate) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_new_project))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchBar(query = state.query, onQuery = { viewModel.onAction(HomeAction.Search(it)) })

            if (state.isLoading) {
                StudioLoadingView()
            } else if (state.projects.isEmpty()) {
                StudioEmptyState(
                    title = stringResource(R.string.home_empty_title),
                    subtitle = stringResource(R.string.home_empty_subtitle),
                    action = {
                        StudioPrimaryButton(
                            text = stringResource(R.string.home_new_project),
                            onClick = { viewModel.onAction(HomeAction.OpenProjectCreate) },
                        )
                    },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.projects, key = { it.id.value }) { project ->
                        ProjectCard(
                            project = project,
                            onOpen = { viewModel.onAction(HomeAction.Open(project)) },
                            onDuplicate = { viewModel.onAction(HomeAction.Duplicate(project)) },
                            onDelete = { viewModel.onAction(HomeAction.Delete(project)) },
                            onArchive = { viewModel.onAction(HomeAction.Archive(project, true)) },
                        )
                    }
                }
            }
        }
    }

    if (state.showTemplatePicker) {
        TemplatePickerSheet(
            templates = state.templates,
            onCreate = { name, templateId, tempo ->
                viewModel.onAction(HomeAction.CreateProject(name, templateId, tempo))
            },
            onDismiss = { viewModel.onAction(HomeAction.DismissProjectCreate) },
        )
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.home_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
    )
}

@Composable
fun ProjectCard(
    project: Project,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    StudioCard(modifier = modifier, onClick = onOpen) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .width(4.dp)
                        .height(32.dp)
                        .background(trackColor(project.colorIndex), RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.home_updated, project.updatedAt.toString().take(10)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_duplicate)) }, onClick = { menuOpen = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_archive)) }, onClick = { menuOpen = false; onArchive() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_delete)) }, onClick = { menuOpen = false; confirmDelete = true })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioBadge("%.0f BPM".format(project.tempo), color = trackColor(project.colorIndex))
                StudioBadge("${project.timeSignature.numerator}/${project.timeSignature.denominator}")
                project.key?.let { StudioBadge(it.display) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.home_delete_confirm_title)) },
            text = { Text(stringResource(R.string.home_delete_confirm_message, project.name)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.home_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.home_cancel)) }
            },
        )
    }
}
