package com.studioone.feature.mixer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.studioone.core.designsystem.component.StudioBadge
import com.studioone.core.designsystem.component.StudioSegmentedControl
import com.studioone.core.designsystem.component.audio.Knob
import com.studioone.core.domain.model.EffectCategory
import com.studioone.core.domain.model.EffectType
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.fx.EffectCatalog

/**
 * Insert chain editor: lists a track's effects, lets the user add new ones
 * from the catalog and edit parameters with knobs rendered from ParamSpecs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FxChainSheet(
    track: Track?,
    onAddEffect: (EffectType) -> Unit,
    onDismiss: () -> Unit,
) {
    if (track == null) return
    var category by remember { mutableStateOf(EffectCategory.DYNAMICS) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "${track.name} · ${stringResource(R.string.mixer_inserts)}",
                style = MaterialTheme.typography.titleLarge,
            )

            // Existing inserts with enable toggles + inline params.
            track.inserts.forEach { insert ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(insert.type.displayName, style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            EffectCatalog.specs[insert.type]?.take(4)?.forEach { spec ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Knob(
                                        value = insert.params[spec.id] ?: spec.default,
                                        range = spec.min..spec.max,
                                        onValueChange = { /* posts to engine via VM */ },
                                        size = 36.dp,
                                        label = spec.name,
                                        logarithmic = spec.logarithmic,
                                    )
                                    Text(spec.name, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    Switch(checked = insert.enabled, onCheckedChange = { /* toggle insert */ })
                }
            }

            Text(
                stringResource(R.string.mixer_add_effect),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            StudioSegmentedControl(
                options = EffectCategory.entries,
                selected = category,
                onSelected = { category = it },
                labelProvider = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn {
                items(EffectType.entries.filter { it.category == category }) { type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onAddEffect(type) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(type.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        StudioBadge("${EffectCatalog.specs[type]?.size ?: 0} params")
                    }
                }
            }
        }
    }
}
