package com.studioone.feature.effects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.core.designsystem.component.StudioBadge
import com.studioone.core.domain.model.library.Preset
import kotlinx.coroutines.flow.StateFlow

/** Browses presets for one target (instrument id or effect type name). */
@Composable
fun PresetBrowser(
    presets: StateFlow<List<Preset>>,
    onApply: (Preset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list by presets.collectAsStateWithLifecycle()
    Column(modifier.padding(12.dp)) {
        Text(stringResource(R.string.effects_presets), style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(list, key = { it.id }) { preset ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onApply(preset) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                        Text(preset.author, style = MaterialTheme.typography.labelSmall)
                    }
                    StudioBadge(preset.category)
                }
            }
        }
    }
}
