package com.studioone.core.domain.repository

import com.studioone.core.domain.model.user.AuthState
import com.studioone.core.domain.model.user.User
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>

    suspend fun signInWithEmail(email: String, password: String): User
    suspend fun signUp(email: String, password: String, displayName: String): User
    suspend fun signInWithOAuth(provider: String): User
    suspend fun signOut()
    suspend fun resetPassword(email: String)
}
