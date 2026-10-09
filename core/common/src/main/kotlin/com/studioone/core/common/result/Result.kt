package com.studioone.core.common.result

import com.studioone.core.common.error.StudioOneException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Three-state result used across repository boundaries so the UI can
 * distinguish "loading", "data", and "failed" deterministically.
 */
sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Failure(val error: StudioOneException) : Result<Nothing>
    data object Loading : Result<Nothing>
}

val Result<*>.succeeded get() = this is Result.Success

fun <T> Result<T>.getOrNull(): T? = (this as? Result.Success)?.data

fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Failure -> this
    Result.Loading -> Result.Loading
}

/** Wraps a suspending body into a [Result], mapping any throwable to a domain error. */
suspend fun <T> asResult(block: suspend () -> T): Result<T> = try {
    Result.Success(block())
} catch (e: StudioOneException) {
    Result.Failure(e)
} catch (e: Exception) {
    Result.Failure(StudioOneException.Unknown(e.message ?: "Unknown error", e))
}

/** Converts a cold Flow<T> into a Flow<Result<T>> emitting Loading first. */
fun <T> Flow<T>.asResult(): Flow<Result<T>> =
    map<T, Result<T>> { Result.Success(it) }
        .onStart { emit(Result.Loading) }
        .catch { emit(Result.Failure(StudioOneException.from(it))) }
