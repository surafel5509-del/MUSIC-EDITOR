package com.studioone.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.AuthRepository
import com.studioone.mobile.core.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val isRegistering: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val signedInUserId: String? = null,   // one-shot nav signal
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun setEmail(v: String) { _uiState.value = _uiState.value.copy(email = v, error = null) }
    fun setPassword(v: String) { _uiState.value = _uiState.value.copy(password = v, error = null) }
    fun setDisplayName(v: String) { _uiState.value = _uiState.value.copy(displayName = v) }
    fun setRegistering(v: Boolean) { _uiState.value = _uiState.value.copy(isRegistering = v) }

    fun submitEmailAuth() {
        val state = _uiState.value
        if (!state.email.contains('@') || state.password.length < 8) {
            _uiState.value = state.copy(error = "Enter a valid email and a password of at least 8 characters.")
            return
        }
        _uiState.value = state.copy(isLoading = true)
        viewModelScope.launch {
            val result = if (state.isRegistering) {
                authRepository.registerWithEmail(state.email, state.password, state.displayName.ifBlank { "Musician" })
            } else {
                authRepository.signInWithEmail(state.email, state.password)
            }
            handle(result)
        }
    }

    fun googleSignIn(idToken: String) = wrap { authRepository.signInWithGoogle(idToken) }
    fun facebookSignIn(token: String) = wrap { authRepository.signInWithFacebook(token) }
    fun appleSignIn(idToken: String, nonce: String) = wrap { authRepository.signInWithApple(idToken, nonce) }

    fun continueAsGuest() = wrap { authRepository.continueAsGuest() }

    private fun wrap(block: suspend () -> DataResult<User>) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch { handle(block()) }
    }

    private fun handle(result: DataResult<User>) {
        _uiState.value = when (result) {
            is DataResult.Success -> _uiState.value.copy(
                isLoading = false, signedInUserId = result.data.id.value, error = null)
            is DataResult.Failure -> _uiState.value.copy(isLoading = false, error = result.error.message)
            DataResult.Loading -> _uiState.value
        }
    }

    fun consumeNavigation() { _uiState.value = _uiState.value.copy(signedInUserId = null) }
}
