package com.studioone.mobile.core.common

/**
 * App-wide result wrapper for data/domain boundaries. Distinct from
 * kotlin.Result so we can carry typed [DataError]s (offline vs. auth vs.
 * entitlement failures drive different UI affordances).
 */
sealed interface DataResult<out T> {
    data class Success<T>(val data: T) : DataResult<T>
    data class Failure(val error: DataError) : DataResult<Nothing>
    data object Loading : DataResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.data
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw error.toException()
        Loading -> IllegalStateException("Result still loading")
    }

    fun <R> map(transform: (T) -> R): DataResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
        Loading -> Loading
    }
}

inline fun <T> dataResultOf(block: () -> T): DataResult<T> = try {
    DataResult.Success(block())
} catch (e: Exception) {
    DataResult.Failure(DataError.from(e))
}

/** Taxonomy of failures — features switch on kind, not on exception types. */
data class DataError(
    val kind: Kind,
    val message: String? = null,
    val code: Int? = null,
    val cause: Throwable? = null,
) {
    enum class Kind {
        NETWORK, TIMEOUT, AUTH, PERMISSION, NOT_FOUND, CONFLICT,
        ENTITLEMENT, STORAGE_FULL, UNSUPPORTED_DEVICE, CANCELLED, UNKNOWN;
    }

    val isRetryable: Boolean
        get() = kind == Kind.NETWORK || kind == Kind.TIMEOUT || kind == Kind.CONFLICT

    fun toException(): Exception =
        DataException(this)

    companion object {
        val OFFLINE = DataError(Kind.NETWORK, "You're offline. Changes are saved locally and will sync.")
        val GENERIC = DataError(Kind.UNKNOWN, "Something went wrong.")

        fun from(t: Throwable): DataError = when (t) {
            is DataException -> t.error
            is java.io.IOException -> DataError(Kind.NETWORK, t.message, cause = t)
            is java.util.concurrent.TimeoutException -> DataError(Kind.TIMEOUT, t.message, cause = t)
            is SecurityException -> DataError(Kind.PERMISSION, t.message, cause = t)
            is kotlinx.coroutines.CancellationException -> throw t // never swallow cancellation
            else -> DataError(Kind.UNKNOWN, t.message, cause = t)
        }
    }
}

class DataException(val error: DataError) : Exception(error.message, error.cause)
