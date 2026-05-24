package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataFreshnessDisplayTest {
    private val nowMillis = 1_779_278_400_000L // 2026-05-20T12:00:00Z

    @Test
    fun hidesWhenSourceIsMissing() {
        assertNull(dataFreshnessDisplay(null, null, nowMillis))
    }

    @Test
    fun hidesUnknownSources() {
        assertNull(dataFreshnessDisplay("fallback", "2026-05-20T12:00:00Z", nowMillis))
    }

    @Test
    fun storageJustNowIsFresh() {
        val display = dataFreshnessDisplay("storage", "2026-05-20T11:59:30Z", nowMillis)
        assertEquals("방금 전", display?.text)
        assertEquals(DataFreshnessBadgeTone.Fresh, display?.tone)
    }

    @Test
    fun storageWithinTenMinutesIsFresh() {
        val display = dataFreshnessDisplay("storage", "2026-05-20T11:55:00Z", nowMillis)
        assertEquals("5분 전", display?.text)
        assertEquals(DataFreshnessBadgeTone.Fresh, display?.tone)
    }

    @Test
    fun storageAfterTenMinutesIsDelayed() {
        val display = dataFreshnessDisplay("storage", "2026-05-20T11:39:00Z", nowMillis)
        assertEquals("21분 전", display?.text)
        assertEquals(DataFreshnessBadgeTone.Delayed, display?.tone)
    }

    @Test
    fun storageAfterOneHourIsStale() {
        val display = dataFreshnessDisplay("storage", "2026-05-20T10:55:00Z", nowMillis)
        assertEquals("1시간 전", display?.text)
        assertEquals(DataFreshnessBadgeTone.Stale, display?.tone)
    }

    @Test
    fun storageAfterOneDayUsesDayText() {
        val display = dataFreshnessDisplay("storage", "2026-05-18T09:00:00Z", nowMillis)
        assertEquals("2일 전", display?.text)
        assertEquals(DataFreshnessBadgeTone.Stale, display?.tone)
    }

    @Test
    fun storageSnapshotShowsPartialData() {
        val display = dataFreshnessDisplay("storage_snapshot", null, nowMillis)
        assertEquals("부분 데이터", display?.text)
        assertEquals(DataFreshnessBadgeTone.Partial, display?.tone)
    }

    @Test
    fun invalidStorageTimestampIsHidden() {
        assertNull(dataFreshnessDisplay("storage", "not-a-date", nowMillis))
    }
}
