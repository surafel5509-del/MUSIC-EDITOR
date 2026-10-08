package com.studioone.mobile.core.analytics.di

import com.studioone.mobile.core.analytics.AnalyticsHub
import com.studioone.mobile.core.analytics.AnalyticsSink
import com.studioone.mobile.core.analytics.FirebaseAnalyticsSink
import com.studioone.mobile.core.datastore.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {
    @Provides
    @Singleton
    fun provideAnalyticsHub(
        firebaseSink: FirebaseAnalyticsSink,
        settings: SettingsRepository,
    ): AnalyticsHub = AnalyticsHub(
        sinks = listOf<AnalyticsSink>(firebaseSink),
        consentProvider = { settings.consentAnalytics.first() },
    )
}
