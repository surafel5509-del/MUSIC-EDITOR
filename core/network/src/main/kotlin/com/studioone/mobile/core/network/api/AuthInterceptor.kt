package com.studioone.mobile.core.network.api

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <JWT>` and the Supabase apikey to every
 * request. Token refresh is handled by the provider (core:data
 * AuthRepository); the interceptor only reads the current token — blocking
 * here is acceptable because OkHttp dispatches on its own pool, never the
 * main or audio threads.
 */
class AuthInterceptor(
    private val config: ApiConfig,
    private val tokenProvider: AccessTokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenProvider.currentToken() }
        val builder = chain.request().newBuilder()
            .header("apikey", config.supabaseAnonKey)
            .header("Accept", "application/json")
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        } else {
            builder.header("Authorization", "Bearer ${config.supabaseAnonKey}")
        }
        return chain.proceed(builder.build())
    }
}
