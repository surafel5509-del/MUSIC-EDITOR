package com.studioone.core.network.di

import com.studioone.core.network.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.HEADERS
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            },
        )
        .build()

    /**
     * Single Supabase client with all plugins used by the app:
     * Auth (email + OAuth), Postgrest (projects metadata), Storage (samples,
     * stems, exports) and Realtime (collaboration channels + presence).
     */
    @Provides
    @Singleton
    fun provideSupabaseClient(okHttpClient: OkHttpClient): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            // The OkHttp Ktor engine on the classpath is auto-discovered; it
            // supports WebSockets, which the Realtime plugin requires.
            install(Auth)
            install(Postgrest)
            install(Storage)
            install(Realtime)
        }
}
