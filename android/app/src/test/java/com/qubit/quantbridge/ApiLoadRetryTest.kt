package com.qubit.quantbridge

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiLoadRetryTest {
    @Test
    fun retryingApiResultRetriesIOExceptionThenSucceeds() = runBlocking {
        var attempts = 0

        val result = retryingApiResult(timeoutMs = 1_000L, attempts = 2, retryDelayMs = 0L) {
            attempts += 1
            if (attempts == 1) throw IOException("timeout")
            "ok"
        }

        assertEquals("ok", result.getOrThrow())
        assertEquals(2, attempts)
    }

    @Test
    fun retryingApiResultDoesNotRetryNonRetryableFailure() = runBlocking {
        var attempts = 0

        val result = retryingApiResult(timeoutMs = 1_000L, attempts = 2, retryDelayMs = 0L) {
            attempts += 1
            error("bad input")
        }

        assertTrue(result.isFailure)
        assertEquals(1, attempts)
    }

    @Test
    fun retryingApiResultRetriesTimeoutCancellationThenSucceeds() = runBlocking {
        var attempts = 0

        val result = retryingApiResult(timeoutMs = 5L, attempts = 2, retryDelayMs = 0L) {
            attempts += 1
            if (attempts == 1) delay(50L)
            "ok"
        }

        assertEquals("ok", result.getOrThrow())
        assertEquals(2, attempts)
    }

    @Test
    fun retryingApiResultRethrowsNonTimeoutCancellation() {
        try {
            runBlocking {
                retryingApiResult(timeoutMs = 1_000L, attempts = 2, retryDelayMs = 0L) {
                    throw CancellationException("screen left")
                }
            }
            fail("Expected non-timeout cancellation to propagate")
        } catch (_: CancellationException) {
        }
    }
}
