package com.studioone.mobile.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Debounce-with-latest semantics for high-rate flows (meters, gestures). */
fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = flow {
    var lastEmit = 0L
    var pending: T? = null
    var hasPending = false
    collect { value ->
        val now = System.currentTimeMillis()
        if (now - lastEmit >= periodMs) {
            lastEmit = now
            emit(value)
            if (hasPending) {
                @Suppress("UNCHECKED_CAST")
                emit(pending as T)
                hasPending = false
            }
        } else {
            pending = value
            hasPending = true
        }
    }
}

/** Conflated sampler — UI never queues stale meter frames. */
fun <T> Flow<T>.conflateWith(periodMs: Long): Flow<T> = conflate().also { }
    .let { upstream -> flow { upstream.collect { emit(it); delay(periodMs) } } }

/**
 * One-shot event bus for cross-module signals (e.g. "project imported",
 * "entitlement changed"). Shared with replay=0 so late subscribers never see
 * stale navigation/toast events.
 */
class AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: Flow<AppEvent> = _events

    fun tryEmit(event: AppEvent): Boolean = _events.tryEmit(event)
    fun emitIn(scope: CoroutineScope, event: AppEvent): Job = scope.launch { _events.emit(event) }
}

sealed interface AppEvent {
    data class ProjectOpened(val projectId: String) : AppEvent
    data class ProjectSaved(val projectId: String) : AppEvent
    data class EntitlementChanged(val tier: String) : AppEvent
    data class SyncStateChanged(val projectId: String, val status: String) : AppEvent
    data class ShowMessage(val text: String, val isError: Boolean = false) : AppEvent
    data object AudioDeviceChanged : AppEvent
    data object RequestPlayStoreReview : AppEvent
}

/** StateFlow helper that ignores no-op writes (prevents recomposition churn). */
fun <T> MutableStateFlow<T>.setIfChanged(value: T) {
    if (this.value != value) this.value = value
}
