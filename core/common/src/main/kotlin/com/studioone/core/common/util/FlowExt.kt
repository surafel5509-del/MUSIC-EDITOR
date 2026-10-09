package com.studioone.core.common.util

import kotlin.time.Duration
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce

/**
 * Throttles a hot flow (metering, fader gestures) to at most one emission per
 * [interval]. Implemented with debounce() semantics inverted via sample() in callers.
 */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.throttleLatest(interval: Duration): Flow<T> = debounce { interval }
