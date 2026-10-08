package com.studioone.core.network.auth

import com.studioone.core.domain.model.user.AuthState
import com.studioone.core.domain.model.user.User
import com.studioone.core.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Repository facade over [SupabaseAuthService]. */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val service: SupabaseAuthService,
) : AuthRepository {
    override fun observeAuthState(): Flow<AuthState> = service.observeAuthState()
    override suspend fun signInWithEmail(email: String, password: String): User =
        service.signInWithEmail(email, password)
    override suspend fun signUp(email: String, password: String, displayName: String): User =
        service.signUp(email, password, displayName)
    override suspend fun signInWithOAuth(provider: String): User =
        service.signInWithOAuthIdToken(provider, idToken = "")
    override suspend fun signOut() = service.signOut()
    override suspend fun resetPassword(email: String) = service.resetPassword(email)
}
