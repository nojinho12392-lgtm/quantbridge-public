package com.example.myapplication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantApiTest {
    @Test
    fun httpRetryPolicyDoesNotHideAuthFailuresAsConnectionFailures() {
        assertFalse(shouldRetryApiHttpStatus(400))
        assertFalse(shouldRetryApiHttpStatus(401))
        assertFalse(shouldRetryApiHttpStatus(409))
        assertFalse(shouldRetryApiHttpStatus(422))
        assertFalse(shouldRetryApiHttpStatus(429))
    }

    @Test
    fun httpRetryPolicyAllowsFallbackForMissingOrUnhealthyBaseUrl() {
        assertTrue(shouldRetryApiHttpStatus(404))
        assertTrue(shouldRetryApiHttpStatus(408))
        assertTrue(shouldRetryApiHttpStatus(500))
        assertTrue(shouldRetryApiHttpStatus(503))
    }
}
