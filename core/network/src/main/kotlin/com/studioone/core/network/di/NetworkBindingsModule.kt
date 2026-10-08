package com.studioone.core.network.di

import com.studioone.core.domain.repository.AuthRepository
import com.studioone.core.domain.repository.CollabRepository
import com.studioone.core.network.auth.AuthRepositoryImpl
import com.studioone.core.network.realtime.CollabRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindingsModule {
    @Binds
    @Singleton
    abstract fun bindCollabRepository(impl: CollabRepositoryImpl): CollabRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
}
