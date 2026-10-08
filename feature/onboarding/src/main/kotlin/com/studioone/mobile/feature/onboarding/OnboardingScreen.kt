package com.studioone.mobile.feature.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studioone.mobile.core.designsystem.components.S1PrimaryButton
import com.studioone.mobile.core.designsystem.components.S1SecondaryButton
import kotlinx.coroutines.launch

/**
 * Onboarding: 4 value-prop pages -> RECORD_AUDIO permission rationale ->
 * quick-start template picker -> (optional) interactive tutorial handoff to
 * the arranger with coach marks. State persists via OnboardingState so a
 * killed process resumes on the right page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    initialPage: Int = 0,
    onRequestAudioPermission: () -> Unit,
    onPickTemplate: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val pages = rememberPagerState(initialPage = initialPage) { 5 }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(state = pages, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> OnboardPage(Icons.Default.GraphicEq, "A full studio in your pocket",
                    "Multitrack recording, MIDI, virtual instruments, and pro mixing — built for Android with a low-latency engine.")
                1 -> OnboardPage(Icons.Default.Mic, "Record in seconds",
                    "Arm a track and go. Punch-in, loop recording, count-in, and monitoring FX keep takes professional.")
                2 -> OnboardPage(Icons.Default.LibraryMusic, "Thousands of sounds",
                    "Royalty-free loops, one-shots, and MIDI packs — searchable by BPM and key, ready to drop in.")
                3 -> OnboardPage(Icons.Default.People, "Make it together",
                    "Real-time collaboration, comments on the timeline, share links, and remix forks.")
                4 -> TemplatePickPage(onPickTemplate)
            }
        }
        // Page dots.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(5) { i ->
                Box(
                    Modifier.size(if (pages.currentPage == i) 10.dp else 6.dp)
                        .background(
                            if (pages.currentPage == i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
            }
        }
        // Bottom actions.
        Column(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
            if (pages.currentPage == 3) {
                S1PrimaryButton("Allow microphone", onClick = onRequestAudioPermission,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
            }
            S1SecondaryButton(
                text = if (pages.currentPage == 4) "Start creating" else "Next",
                onClick = {
                    if (pages.currentPage < 4) scope.launch { pages.animateScrollToPage(pages.currentPage + 1) }
                    else onSkip()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (pages.currentPage < 4) {
            Text("Skip", modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)
                .background(MaterialTheme.colorScheme.background),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OnboardPage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TemplatePickPage(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Start with a template", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("You can always start empty — templates just set up tracks and sounds.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        listOf(
            "BEAT" to "Drums, 808, keys — 140 BPM trap-ready",
            "SONG" to "Vocal, guitar, keys, drums — 4/4 at 96 BPM",
            "PODCAST" to "Two treated voice tracks + music bed",
            "EMPTY" to "Blank session, my rules",
        ).forEach { (id, desc) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant,
                        com.studioone.mobile.core.designsystem.theme.S1Shapes.small)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(id.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium)
                    Text(desc, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                S1SecondaryButton("Use", onClick = { onPick(id) })
            }
        }
    }
}
