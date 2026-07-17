package com.qubit.quantbridge

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException

private const val HTTP_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_MIN = 500

internal suspend fun <T> retryingApiResult(
    timeoutMs: Long,
    attempts: Int = 2,
    retryDelayMs: Long = 1_500L,
    block: suspend () -> T
): Result<T> {
    val safeAttempts = attempts.coerceAtLeast(1)
    var lastFailure: Throwable? = null
    var finalResult: Result<T>? = null

    var attempt = 0
    while (attempt < safeAttempts && finalResult == null) {
        val result = runCatching { withTimeout(timeoutMs) { block() } }
        result.exceptionOrNull()?.throwIfNonTimeoutCancellation()

        val failure = result.exceptionOrNull()
        if (failure == null) {
            finalResult = result
        } else {
            lastFailure = failure
            val lastAttempt = attempt == safeAttempts - 1
            if (lastAttempt || !failure.isRetryableApiLoadFailure()) {
                finalResult = result
            } else {
                delay(retryDelayMs * (attempt + 1))
            }
        }
        attempt += 1
    }

    return finalResult ?: Result.failure(lastFailure ?: IllegalStateException("API request failed"))
}

internal fun Throwable.throwIfNonTimeoutCancellation() {
    if (this is CancellationException && this !is TimeoutCancellationException) throw this
}

internal fun Throwable.isRetryableApiLoadFailure(): Boolean {
    return when (this) {
        is TimeoutCancellationException -> true
        is IOException -> true
        is HttpException -> code().isRetryableHttpStatus()
        else -> localizedMessage.orEmpty().lowercase(Locale.US).let { message ->
            message.contains("timeout") ||
                message.contains("timed out") ||
                message.contains("failed to connect") ||
                message.contains("connection reset") ||
                message.contains("connection refused") ||
                message.contains("canceled")
        }
    }
}

private fun Int.isRetryableHttpStatus(): Boolean {
    return this == HTTP_TIMEOUT || this == HTTP_TOO_MANY_REQUESTS || this >= HTTP_SERVER_ERROR_MIN
}
