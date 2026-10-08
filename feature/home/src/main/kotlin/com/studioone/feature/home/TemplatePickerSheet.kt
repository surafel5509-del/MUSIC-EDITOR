package com.studioone.feature.home

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.studioone.core.designsystem.component.StudioBadge
import com.studioone.core.designsystem.component.StudioPrimaryButton
import com.studioone.core.domain.model.ProjectTemplate
import kotlin.math.roundToInt

/**
 * Bottom sheet for creating a project: name, template, tempo. Key / time
 * signature come from the template; advanced settings live in the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatePickerSheet(
    templates: List<ProjectTemplate>,
    onCreate: (name: String, templateId: String?, tempo: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf<ProjectTemplate?>(null) }
    var tempo by remember { mutableDoubleStateOf(120.0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.home_templates_title), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.home_project_name_label)) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                singleLine = true,
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.home_tempo), modifier = Modifier.weight(1f))
                Text("%.0f BPM".format(tempo), style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = tempo.toFloat(),
                onValueChange = { tempo = it.toDouble().roundToInt().toDouble() },
                valueRange = 40f..220f,
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    TemplateRow(
                        title = "Blank",
                        subtitle = "Empty session — bring your own tracks",
                        selected = selectedTemplate == null,
                        onClick = { selectedTemplate = null },
                    )
                }
                items(templates) { template ->
                    TemplateRow(
                        title = template.name,
                        subtitle = template.description,
                        selected = selectedTemplate?.id == template.id,
                        onClick = { selectedTemplate = template; tempo = template.tempo },
                        badge = template.category.name.lowercase().replaceFirstChar { it.uppercase() },
                    )
                }
            }

            StudioPrimaryButton(
                text = stringResource(R.string.home_create),
                onClick = {
                    onCreate(
                        name.ifBlank { selectedTemplate?.name ?: "Untitled" },
                        selectedTemplate?.id,
                        if (selectedTemplate == null) tempo else null,
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun TemplateRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (badge != null) StudioBadge(badge)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
