package com.studioone.core.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** State for a runtime permission request flow. */
data class PermissionFlowState(
    val permission: String,
    val granted: Boolean,
    val shouldShowRationale: Boolean = false,
)

/** Compose-friendly runtime permission flow handle. */
class PermissionFlow internal constructor(
    val state: PermissionFlowState,
    val launch: () -> Unit,
)

/**
 * Compose-friendly runtime permission flow. Used by the recorder
 * (RECORD_AUDIO), BLE MIDI scanner (BLUETOOTH_SCAN/CONNECT) and exporter
 * (POST_NOTIFICATIONS for background bounce progress).
 */
@Composable
fun rememberPermissionFlow(permission: String): PermissionFlow {
    val context = LocalContext.current
    var state by remember(permission) {
        mutableStateOf(
            PermissionFlowState(
                permission = permission,
                granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
            ),
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state = state.copy(granted = granted)
    }
    LaunchedEffect(permission) {
        state = state.copy(
            granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    return PermissionFlow(state) { launcher.launch(permission) }
}

/** All permissions the recording pipeline needs, honoring API levels. */
fun recordingPermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
