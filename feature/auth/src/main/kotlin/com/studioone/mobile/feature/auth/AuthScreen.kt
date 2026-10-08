package com.studioone.mobile.feature.auth

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studioone.mobile.core.designsystem.components.S1PrimaryButton
import com.studioone.mobile.core.designsystem.components.S1SecondaryButton
import com.studioone.mobile.core.designsystem.theme.S1Colors

/**
 * Sign-in / sign-up. Google uses the Credential Manager flow (wired in the
 * app module via rememberLauncherForActivityResult); Facebook SDK & Apple
 * (AppAuth web flow with PKCE + nonce) are triggered from their buttons and
 * hand tokens to the ViewModel. Guest mode is always available and fully
 * functional offline.
 */
@Composable
fun AuthScreen(
    onSignedIn: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onFacebookSignIn: () -> Unit,
    onAppleSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedInUserId) {
        if (state.signedInUserId != null) { viewModel.consumeNavigation(); onSignedIn() }
    }

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).background(S1Colors.ElectricCyan.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, contentDescription = null,
                tint = S1Colors.ElectricCyan, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("StudioOne", style = MaterialTheme.typography.displayMedium)
        Text("Record. Produce. Share.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.email, onValueChange = viewModel::setEmail,
            label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if (state.isRegistering) {
            OutlinedTextField(
                value = state.displayName, onValueChange = viewModel::setDisplayName,
                label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = state.password, onValueChange = viewModel::setPassword,
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.spCompat())
        }

        Spacer(Modifier.height(16.dp))
        S1PrimaryButton(
            text = if (state.isRegistering) "Create account" else "Sign in",
            onClick = viewModel::submitEmailAuth,
            loading = state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { viewModel.setRegistering(!state.isRegistering) }) {
            Text(if (state.isRegistering) "I already have an account" else "New here? Create an account")
        }

        Spacer(Modifier.height(8.dp))
        ProviderButton("Continue with Google", onGoogleSignIn)
        ProviderButton("Continue with Facebook", onFacebookSignIn)
        ProviderButton("Continue with Apple", onAppleSignIn)
        Spacer(Modifier.height(8.dp))
        S1SecondaryButton("Continue as guest", onClick = viewModel::continueAsGuest,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(
            "By continuing you agree to our Terms of Service and Privacy Policy. Guest projects stay on this device until you sign in.",
            fontSize = 10.spCompat(), color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProviderButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label)
    }
}

private fun Int.spCompat() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
