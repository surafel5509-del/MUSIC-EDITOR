package com.studioone.core.network.auth

import com.studioone.core.common.error.StudioOneException
import com.studioone.core.domain.model.UserId
import com.studioone.core.domain.model.user.AuthState
import com.studioone.core.domain.model.user.SubscriptionPlan
import com.studioone.core.domain.model.user.User
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Auth facade over Supabase GoTrue. */
@Singleton
class SupabaseAuthService @Inject constructor(private val supabase: SupabaseClient) {

    fun observeAuthState(): Flow<AuthState> =
        supabase.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    val user = status.session.user
                    AuthState.SignedIn(
                        User(
                            id = UserId(user?.id ?: "anonymous"),
                            email = user?.email.orEmpty(),
                            displayName = user?.userMetadata?.get("display_name")?.toString()
                                ?.trim('"') ?: (user?.email ?: "Producer"),
                            avatarUrl = user?.userMetadata?.get("avatar_url")?.toString()?.trim('"'),
                            plan = SubscriptionPlan.FREE,
                        ),
                    )
                }
                is SessionStatus.NotAuthenticated -> AuthState.SignedOut
                SessionStatus.Initializing -> AuthState.SignedOut
                is SessionStatus.RefreshFailure -> AuthState.Error("Session refresh failed")
            }
        }

    suspend fun signInWithEmail(email: String, password: String): User {
        try {
            supabase.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        } catch (e: Exception) {
            throw StudioOneException.Auth("Sign-in failed: ${e.message}", e)
        }
        return currentUserOrThrow()
    }

    suspend fun signUp(email: String, password: String, displayName: String): User {
        try {
            supabase.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                data = buildJsonObject { put("display_name", displayName) }
            }
        } catch (e: Exception) {
            throw StudioOneException.Auth("Sign-up failed: ${e.message}", e)
        }
        return currentUserOrThrow()
    }

    suspend fun signInWithOAuthIdToken(provider: String, idToken: String): User {
        try {
            supabase.auth.signInWith(IDToken) {
                this.provider = io.github.jan.supabase.auth.providers.Google
                this.idToken = idToken
            }
        } catch (e: Exception) {
            throw StudioOneException.Auth("OAuth sign-in failed: ${e.message}", e)
        }
        return currentUserOrThrow()
    }

    suspend fun signOut() = supabase.auth.signOut()

    suspend fun resetPassword(email: String) = supabase.auth.resetPasswordForEmail(email)

    private fun currentUserOrThrow(): User {
        val user = supabase.auth.currentUserOrNull()
            ?: throw StudioOneException.Auth("Session not established")
        return User(
            id = UserId(user.id),
            email = user.email.orEmpty(),
            displayName = user.userMetadata?.get("display_name")?.toString()?.trim('"') ?: user.email.orEmpty(),
            avatarUrl = user.userMetadata?.get("avatar_url")?.toString()?.trim('"'),
        )
    }
}
