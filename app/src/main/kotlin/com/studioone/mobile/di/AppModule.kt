package com.studioone.mobile.di

import com.studioone.core.common.dispatcher.DefaultDispatcherProvider
import com.studioone.core.common.dispatcher.DispatcherProvider
import com.studioone.core.common.dispatcher.IoDispatcher
import com.studioone.core.common.dispatcher.MainDispatcher
import com.studioone.core.domain.editor.EditorSessionHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** The open-project session shared by editor / mixer / piano roll / instruments. */
    @Provides
    @Singleton
    fun provideEditorSessionHolder(): EditorSessionHolder = EditorSessionHolder()
}
