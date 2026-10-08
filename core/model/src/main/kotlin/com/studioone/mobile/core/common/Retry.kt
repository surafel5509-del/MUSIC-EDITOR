package com.studioone.mobile.core.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential-backoff retry with full jitter (the AWS-recommended policy that
 * avoids thundering-herd on sync servers). Used by the sync engine and all
 * network calls that touch collaboration ops.
 */
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 5,
    initialDelayMs: Long = 250,
    maxDelayMs: Long = 15_000,
    jitter: Random = Random.Default,
    retryOn: (Throwable) -> Boolean = { it is java.io.IOException || it is kotlinx.coroutines.TimeoutCancellationException },
    block: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        attempt++
        try {
            return block()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (attempt >= maxAttempts || !retryOn(t)) throw t
            val exp = min(maxDelayMs.toDouble(), initialDelayMs * 2.0.pow(attempt - 1))
            delay(jitter.nextLong(exp.toLong()) + 1) // full jitter: [1, exp)
        }
    }
}

/** Same policy as a cold Flow that emits attempts (for UI progress). */
fun <T> retryFlow(
    maxAttempts: Int = 5,
    block: suspend () -> T,
): Flow<Attempt<T>> = flow {
    var attempt = 0
    while (true) {
        attempt++
        try {
            emit(Attempt.Success(block(), attempt))
            return@flow
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (attempt >= maxAttempts) {
                emit(Attempt.Failed(t, attempt))
                return@flow
            }
            emit(Attempt.Retrying(t, attempt))
            delay(min(15_000L, 250L * 2.0.pow(attempt - 1).toLong()))
        }
    }
}

sealed interface Attempt<out T> {
    data class Success<T>(val value: T, val attempts: Int) : Attempt<T>
    data class Retrying(val error: Throwable, val attempt: Int) : Attempt<Nothing>
    data class Failed(val error: Throwable, val attempts: Int) : Attempt<Nothing>
}
