package com.studioone.mobile.core.data.di

import android.content.Context
import com.studioone.mobile.core.common.DefaultDispatcherProvider
import com.studioone.mobile.core.common.DispatcherProvider
import com.studioone.mobile.core.data.repo.AuthRepositoryImpl
import com.studioone.mobile.core.data.repo.AudioSettingsRepositoryImpl
import com.studioone.mobile.core.data.repo.CollaborationRepositoryImpl
import com.studioone.mobile.core.data.repo.CurrentUserProvider
import com.studioone.mobile.core.data.repo.EntitlementRepositoryImpl
import com.studioone.mobile.core.data.repo.ExportRepositoryImpl
import com.studioone.mobile.core.data.repo.FilesDirProvider
import com.studioone.mobile.core.data.repo.FilesDirProviderImpl
import com.studioone.mobile.core.data.repo.LibraryRepositoryImpl
import com.studioone.mobile.core.data.repo.ProfileRepositoryImpl
import com.studioone.mobile.core.data.repo.ProjectDocumentStore
import com.studioone.mobile.core.data.repo.ProjectDocumentStoreImpl
import com.studioone.mobile.core.data.repo.ProjectRepositoryImpl
import com.studioone.mobile.core.data.repo.SampleRepositoryImpl
import com.studioone.mobile.core.data.repo.SocialRepositoryImpl
import com.studioone.mobile.core.data.repo.TrackRepositoryImpl
import com.studioone.mobile.core.domain.AudioSettingsRepository
import com.studioone.mobile.core.domain.AuthRepository
import com.studioone.mobile.core.domain.CollaborationRepository
import com.studioone.mobile.core.domain.EntitlementRepository
import com.studioone.mobile.core.domain.ExportRepository
import com.studioone.mobile.core.domain.LibraryRepository
import com.studioone.mobile.core.domain.ProfileRepository
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.domain.SampleRepository
import com.studioone.mobile.core.domain.SocialRepository
import com.studioone.mobile.core.domain.TrackRepository
import com.studioone.mobile.core.network.api.AccessTokenProvider
import com.studioone.mobile.core.network.api.ApiConfig
import com.studioone.mobile.core.network.api.AuthInterceptor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.studioone.mobile.core.network.api.StudioOneApi
import java.util.concurrent.TimeUnit

/** API configuration injected from BuildConfig (set by CI secrets). */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideApiConfig(): ApiConfig = ApiConfig(
        supabaseUrl = BuildConfigFields.SUPABASE_URL,
        supabaseAnonKey = BuildConfigFields.SUPABASE_ANON_KEY,
    )

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    @Provides
    @Singleton
    fun provideOkHttp(config: ApiConfig, auth: AuthRepositoryImpl): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC // never BODY: tokens/PII
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(config, AccessTokenProvider { auth.accessToken() }))
            .addInterceptor(logging)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json, config: ApiConfig): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.functionsBaseUrl + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): StudioOneApi = retrofit.create(StudioOneApi::class.java)

    @Provides
    @Singleton
    fun provideDispatchers(): DispatcherProvider = DefaultDispatcherProvider()
}

/** Backend endpoints — real values are injected via local.properties/CI. */
object BuildConfigFields {
    val SUPABASE_URL: String = System.getenv("S1_SUPABASE_URL") ?: "https://studioone.supabase.co"
    val SUPABASE_ANON_KEY: String = System.getenv("S1_SUPABASE_ANON_KEY") ?: "public-anon-key-placeholder"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindAuth(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindCurrentUser(impl: AuthRepositoryImpl): CurrentUserProvider

    @Binds @Singleton
    abstract fun bindProjects(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds @Singleton
    abstract fun bindTracks(impl: TrackRepositoryImpl): TrackRepository

    @Binds @Singleton
    abstract fun bindSamples(impl: SampleRepositoryImpl): SampleRepository

    @Binds @Singleton
    abstract fun bindLibrary(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds @Singleton
    abstract fun bindCollaboration(impl: CollaborationRepositoryImpl): CollaborationRepository

    @Binds @Singleton
    abstract fun bindSocial(impl: SocialRepositoryImpl): SocialRepository

    @Binds @Singleton
    abstract fun bindExport(impl: ExportRepositoryImpl): ExportRepository

    @Binds @Singleton
    abstract fun bindEntitlements(impl: EntitlementRepositoryImpl): EntitlementRepository

    @Binds @Singleton
    abstract fun bindProfile(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds @Singleton
    abstract fun bindAudioSettings(impl: AudioSettingsRepositoryImpl): AudioSettingsRepository

    @Binds @Singleton
    abstract fun bindFiles(impl: FilesDirProviderImpl): FilesDirProvider

    @Binds @Singleton
    abstract fun bindDocumentStore(impl: ProjectDocumentStoreImpl): ProjectDocumentStore
}
