package com.qubit.quantbridge

import kotlinx.coroutines.CancellationException

internal fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}

internal fun <T> Result<T>.rethrowCancellation(): Result<T> =
    onFailure { it.throwIfCancellation() }
