package com.studioone.mobile.feature.social

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.FeedTab
import com.studioone.mobile.core.model.Post
import com.studioone.mobile.core.model.PostKind

/** Community feed: tabs, post cards with inline waveform player, like/comment/share. */
@Composable
fun FeedScreen(
    onOpenPost: (String) -> Unit,
    onPlay: (Post) -> Unit,
    onStopPlay: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SocialViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TabRow(selectedTabIndex = FeedTab.entries.indexOf(state.filter.tab)) {
            FeedTab.entries.take(5).forEach { tab ->
                Tab(
                    selected = state.filter.tab == tab,
                    onClick = { viewModel.loadTab(tab) },
                    text = { Text(tab.displayName, fontSize = 12.sp) },
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.posts, key = { it.id.value }) { post ->
                PostCard(
                    post = post,
                    isPlaying = state.playingPostId == post.id.value,
                    onPlay = {
                        if (state.playingPostId == post.id.value) { onStopPlay(); viewModel.setPlaying(null) }
                        else { onPlay(post); viewModel.setPlaying(post.id.value) }
                    },
                    onLike = { viewModel.toggleLike(post) },
                    onOpen = { onOpenPost(post.id.value) },
                )
            }
            if (state.isLoading) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
            if (state.cursor != null) {
                item { Text("Load more", color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.loadMore() }.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun PostCard(post: Post, isPlaying: Boolean, onPlay: () -> Unit, onLike: () -> Unit, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = S1Shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (post.author?.avatarUrl != null) {
                    AsyncImage(model = post.author?.avatarUrl, contentDescription = null,
                        modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } else {
                    Box(Modifier.size(36.dp).background(
                        S1Colors.TrackPalette[(post.author?.handle?.hashCode() ?: 0).and(0x7FFFFFFF) % 9], CircleShape))
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(post.author?.displayName ?: "Musician", style = MaterialTheme.typography.labelLarge)
                    Text(post.kind.label(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (post.isPremiumOnly) {
                    Text("PAID", fontSize = 9.sp, color = S1Colors.SunsetAmber, fontWeight = FontWeight.Bold)
                }
            }
            post.text?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (post.mediaUrl != null) {
                Spacer(Modifier.height(10.dp))
                // Waveform/preview strip with play toggle.
                Surface(color = MaterialTheme.colorScheme.surface, shape = S1Shapes.small,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(S1Colors.ElectricCyan.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Stop" else "Play",
                                tint = S1Colors.ElectricCyan)
                        }
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(post.metadataLabel(), style = MaterialTheme.typography.labelSmall)
                            WaveformStrip(Modifier.fillMaxWidth().height(28.dp), seed = post.id.value.hashCode())
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ActionIcon(
                    icon = if (post.likedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    count = post.likeCount,
                    tint = if (post.likedByMe) S1Colors.StudioMagenta else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onLike,
                )
                ActionIcon(Icons.Default.ChatBubbleOutline, post.commentCount, MaterialTheme.colorScheme.onSurfaceVariant, onOpen)
                ActionIcon(Icons.Default.Share, 0, MaterialTheme.colorScheme.onSurfaceVariant, onOpen)
                Spacer(Modifier.weight(1f))
                Text("${post.playCount} plays", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int,
                       tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClick)) {
        androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        if (count > 0) {
            Spacer(Modifier.size(4.dp))
            Text("$count", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Deterministic pseudo-waveform strip for feed cards (real waveform PNGs come from the CDN). */
@Composable
private fun WaveformStrip(modifier: Modifier, seed: Int) {
    val color = S1Colors.ElectricCyan
    Box(modifier) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val rnd = java.util.Random(seed.toLong())
            val bars = (size.width / 4f).toInt()
            for (i in 0 until bars) {
                val h = (0.15f + rnd.nextFloat() * 0.85f) * size.height
                drawRect(
                    color = color.copy(alpha = 0.5f),
                    topLeft = androidx.compose.ui.geometry.Offset(i * 4f, (size.height - h) / 2),
                    size = androidx.compose.ui.geometry.Size(2.5f, h),
                )
            }
        }
    }
}

private fun PostKind.label() = when (this) {
    PostKind.TRACK -> "Track"
    PostKind.REMIX -> "Remix"
    PostKind.WIP -> "Work in progress"
    PostKind.PODCAST_EPISODE -> "Podcast episode"
    PostKind.TEXT -> "Post"
    PostKind.CHALLENGE_ENTRY -> "Challenge entry"
    PostKind.LIVE_STREAM -> "Live"
}

private fun Post.metadataLabel(): String = buildString {
    bpm?.let { append("${it.toInt()} BPM") }
    key?.let { if (isNotEmpty()) append(" · "); append(it.toString()) }
    genre?.let { if (isNotEmpty()) append(" · "); append(it) }
    if (isEmpty()) append("Audio")
}
