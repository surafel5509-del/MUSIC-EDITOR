package com.studioone.core.common.dispatcher

import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher

/** Abstraction over coroutine dispatchers so tests can inject a test dispatcher. */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class MainDispatcher
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class DefaultDispatcher
