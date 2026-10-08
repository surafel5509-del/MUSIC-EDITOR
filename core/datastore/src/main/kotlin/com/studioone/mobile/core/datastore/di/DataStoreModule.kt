package com.studioone.mobile.core.datastore.di

import com.studioone.mobile.core.common.AppEventBus
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun provideEventBus(): AppEventBus = AppEventBus()
}
