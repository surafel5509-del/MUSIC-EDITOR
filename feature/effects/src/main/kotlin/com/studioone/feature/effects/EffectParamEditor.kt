package com.studioone.feature.effects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studioone.core.designsystem.component.audio.Knob
import com.studioone.core.domain.model.EffectInstance
import com.studioone.core.domain.model.EffectType
import com.studioone.core.domain.model.fx.EffectCatalog
import com.studioone.core.domain.model.fx.ParamSpec

/**
 * Generic parameter editor driven entirely by [EffectCatalog] metadata.
 * Shared by the mixer insert sheet, rack view and preset editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffectParamEditor(
    type: EffectType,
    instance: EffectInstance,
    onParamChange: (paramId: String, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val specs = EffectCatalog.specs[type] ?: emptyList()
    FlowRow(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        specs.forEach { spec ->
            ParamKnob(
                spec = spec,
                value = instance.params[spec.id] ?: spec.default,
                onValueChange = { onParamChange(spec.id, it) },
            )
        }
    }
}

@Composable
fun ParamKnob(
    spec: ParamSpec,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Knob(
            value = value,
            range = spec.min..spec.max,
            onValueChange = onValueChange,
            size = 52.dp,
            label = spec.name,
            logarithmic = spec.logarithmic,
        )
        Text(spec.name, style = MaterialTheme.typography.labelSmall)
        Text(
            formatParam(spec, value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/** Renders a parameter value with its unit, honoring log-scaled params. */
fun formatParam(spec: ParamSpec, value: Float): String {
    val number = when {
        spec.unit == "Hz" && value >= 1000 -> "%.1fk".format(value / 1000)
        spec.unit == "Hz" -> "%.0f".format(value)
        spec.unit == "ms" && value >= 1000 -> "%.2fs".format(value / 1000)
        spec.unit == "ms" -> "%.0f".format(value)
        else -> "%.1f".format(value)
    }
    return if (spec.unit.isBlank()) number else "$number ${spec.unit}"
}
