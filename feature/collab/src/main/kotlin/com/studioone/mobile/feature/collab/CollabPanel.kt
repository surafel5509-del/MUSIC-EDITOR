package com.studioone.mobile.feature.collab

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.S1PrimaryButton
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.Comment
import com.studioone.mobile.core.model.ProjectRole

/**
 * Collaboration side panel: timestamped comments anchored to the timeline,
 * resolve/unresolve, and scoped share-link creation. On phones this is a
 * bottom sheet; on tablets/foldables it docks beside the arranger (the app
 * nav host chooses the container).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollabPanel(
    onNavigateToComment: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollabViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showShareSheet by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Comments", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showShareSheet = true }) {
                Icon(Icons.Default.PersonAdd, "Invite collaborators")
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.comments, key = { it.id.value }) { comment ->
                CommentRow(comment = comment, onClickAnchor = { comment.anchorFrame?.let(onNavigateToComment) },
                    onResolve = { viewModel.resolve(comment) })
            }
        }
        // Composer.
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.newCommentText,
                    onValueChange = viewModel::setCommentText,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            state.anchorFrame?.let { "Comment @ ${it / 48000}s…" } ?: "Add a comment…",
                        )
                    },
                    maxLines = 3,
                )
                IconButton(onClick = viewModel::submitComment,
                    enabled = state.newCommentText.isNotBlank()) {
                    Icon(Icons.Default.Send, "Post comment", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showShareSheet) {
        ModalBottomSheet(onDismissRequest = { showShareSheet = false }) {
            Column(Modifier.padding(24.dp).fillMaxWidth()) {
                Text("Invite collaborators", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ProjectRole.EDITOR, ProjectRole.COMMENTER, ProjectRole.VIEWER).forEach { role ->
                        FilterChip(
                            selected = state.shareRole == role,
                            onClick = { viewModel.setShareRole(role) },
                            label = { Text(role.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                S1PrimaryButton(
                    text = if (state.isCreatingLink) "Creating…" else "Create link (72h)",
                    onClick = viewModel::createShareLink,
                    enabled = !state.isCreatingLink,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.shareLink?.let { link ->
                    Spacer(Modifier.height(12.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = S1Shapes.small) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("studioone.app/j/${link.token}", fontSize = 12.sp,
                                textDecoration = TextDecoration.Underline, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, onClickAnchor: () -> Unit, onResolve: () -> Unit) {
    Surface(
        color = if (comment.resolved) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = S1Shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(
                    S1Colors.TrackPalette[comment.authorName.hashCode().and(0x7FFFFFFF) % 9], CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(comment.authorName, style = MaterialTheme.typography.labelLarge,
                    textDecoration = if (comment.resolved) TextDecoration.LineThrough else null)
                Spacer(Modifier.weight(1f))
                comment.anchorFrame?.let { frame ->
                    TextButton(onClick = onClickAnchor) {
                        Text("%02d:%02d".format(frame / 48000 / 60, (frame / 48000) % 60), fontSize = 11.sp)
                    }
                }
                IconButton(onClick = onResolve, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Check, if (comment.resolved) "Unresolve" else "Resolve",
                        tint = if (comment.resolved) S1Colors.MeterGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(comment.text, style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (comment.resolved) TextDecoration.LineThrough else null)
        }
    }
}
