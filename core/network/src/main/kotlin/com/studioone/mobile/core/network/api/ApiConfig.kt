package com.studioone.mobile.core.network.api

import kotlinx.serialization.Serializable

/**
 * Backend endpoints. Production uses Supabase (Postgres + PostgREST +
 * Realtime + Storage + Edge Functions). Every URL is injected via BuildConfig
 * fields set from CI secrets — see docs/BACKEND.md for provisioning steps.
 */
@Serializable
data class ApiConfig(
    val supabaseUrl: String,             // https://<project>.supabase.co
    val supabaseAnonKey: String,         // public anon key (RLS enforces security)
    val functionsBaseUrl: String = "$supabaseUrl/functions/v1",
    val realtimeBaseUrl: String = supabaseUrl.replace("https://", "wss://") + "/realtime/v1",
    val storageBaseUrl: String = "$supabaseUrl/storage/v1",
    val restBaseUrl: String = "$supabaseUrl/rest/v1",
    val cdnBaseUrl: String = "",         // optional CDN in front of storage
)

/** Auth token provider — implemented by core:data's AuthRepository (avoids cycles). */
fun interface AccessTokenProvider {
    suspend fun currentToken(): String?
}

/** Current user id provider (same anti-cycle pattern). */
fun interface CurrentUserIdProvider {
    suspend fun currentUserId(): String?
}
