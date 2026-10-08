package com.studioone.mobile.feature.paywall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.S1PrimaryButton
import com.studioone.mobile.core.designsystem.theme.S1Colors
import com.studioone.mobile.core.designsystem.theme.S1Shapes
import com.studioone.mobile.core.model.Product

/** Subscription paywall: Pro benefits, monthly/annual choice, restore, legal links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PaywallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadProducts() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WorkspacePremium, null, tint = S1Colors.SunsetAmber,
                    modifier = Modifier.size(32.dp))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("StudioOne Pro", style = MaterialTheme.typography.headlineSmall)
                    Text("Everything unlocked. Cancel anytime.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            listOf(
                "Unlimited projects & 64+ tracks",
                "20 GB cloud storage + version history",
                "All instruments, effects & premium packs",
                "Lossless export (WAV/FLAC) + stem export",
                "Real-time collaboration with 8 people",
                "No ads, priority rendering",
            ).forEach { benefit ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = S1Colors.MeterGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(benefit, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(20.dp))
            state.products.filter { it.productId.startsWith("sub_") }.forEach { product ->
                ProductRow(product, selected = state.selectedProductId == product.productId,
                    onClick = { viewModel.selectProduct(product.productId) })
            }
            Spacer(Modifier.height(16.dp))
            S1PrimaryButton(
                text = if (state.isPurchasing) "Processing…" else "Start free trial",
                onClick = { viewModel.purchase { onDismiss() } },
                enabled = !state.isPurchasing && state.selectedProductId != null,
                modifier = Modifier.fillMaxWidth(),
            )
            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("Restore purchases", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                    modifier = Modifier.padding(end = 20.dp).let { m ->
                        m.background(MaterialTheme.colorScheme.background).padding(4.dp)
                    })
                Text("Terms", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Privacy", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProductRow(product: Product, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = S1Shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (product.productId.endsWith("annual")) "Annual (save ~40%)" else "Monthly",
                    fontWeight = FontWeight.SemiBold,
                )
                product.freeTrialDays.takeIf { it > 0 }?.let {
                    Text("${it}-day free trial", fontSize = 11.sp, color = S1Colors.MeterGreen)
                }
            }
            Text(product.formattedPrice ?: "$%.2f".format(product.priceMicros / 1_000_000.0),
                fontWeight = FontWeight.Bold)
            Text(
                if (product.productId.endsWith("annual")) "/yr" else "/mo",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
