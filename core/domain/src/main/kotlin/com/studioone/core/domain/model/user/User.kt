package com.studioone.core.domain.model.user

import com.studioone.core.domain.model.UserId

enum class SubscriptionPlan { FREE, CREATOR, PRO }

data class User(
    val id: UserId,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val plan: SubscriptionPlan = SubscriptionPlan.FREE,
)

/** Auth session state exposed to the UI. */
sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val user: User) : AuthState
    data class Error(val message: String) : AuthState
}
