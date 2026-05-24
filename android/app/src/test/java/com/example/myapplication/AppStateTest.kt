package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {
    @Test
    fun normalizeWatchlistItemFillsMissingFields() {
        val item = WatchlistItem(
            ticker = " 005930.ks ",
            name = "",
            market = "",
            currency = "",
            note = "",
            addedAt = "2026-05-13T09:00:00Z"
        )

        val normalized = normalizeWatchlistItem(item)

        assertEquals("005930.KS", normalized.ticker)
        assertEquals("005930.KS", normalized.name)
        assertEquals("KR", normalized.market)
        assertEquals("KRW", normalized.currency)
        assertEquals("Watchlist", normalized.note)
    }

    @Test
    fun mergeWatchlistsKeepsLocalVersionWhenDuplicated() {
        val remote = listOf(
            WatchlistItem("AAPL", "Apple", "US", "USD", "Remote", "2026-05-12"),
            WatchlistItem("MSFT", "Microsoft", "US", "USD", "Remote", "2026-05-12")
        )
        val local = listOf(
            WatchlistItem(" aapl ", "Apple Local", "", "", "Local", "2026-05-13"),
            WatchlistItem("005930.KS", "삼성전자", "", "", "", "2026-05-13")
        )

        val merged = mergeWatchlists(local, remote)

        assertEquals(listOf("AAPL", "MSFT", "005930.KS"), merged.map { it.ticker })
        assertEquals("Apple Local", merged.first { it.ticker == "AAPL" }.name)
        assertEquals("Local", merged.first { it.ticker == "AAPL" }.note)
        assertEquals("KR", merged.first { it.ticker == "005930.KS" }.market)
    }
}
