package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPreferencesRepositoryTest {
    @Test
    fun watchlistCodecPreservesUserMetadata() {
        val items = listOf(
            WatchlistItem(
                ticker = " aapl ",
                name = "Apple",
                market = "US",
                currency = "USD",
                note = "Watchlist",
                addedAt = "2026-05-20",
                tags = listOf("AI", " 장기 "),
                memo = "core holding",
                alertOptions = listOf("price")
            )
        )

        val decoded = decodeWatchlistItems(encodeWatchlistItems(items))

        assertEquals("AAPL", decoded.single().ticker)
        assertEquals(listOf("AI", "장기"), decoded.single().tags)
        assertEquals("core holding", decoded.single().memo)
        assertEquals(listOf("price"), decoded.single().alertOptions)
    }

    @Test
    fun watchlistCodecFallsBackToEmptyListForInvalidJson() {
        assertTrue(decodeWatchlistItems("{broken").isEmpty())
    }

    @Test
    fun pendingWatchlistCodecPreservesDeleteAndSaveOperations() {
        val item = WatchlistItem("MSFT", "Microsoft", "US", "USD", "Watchlist", "2026-05-20")
        val operations = listOf(
            PendingWatchlistOperation("save", "msft", item),
            PendingWatchlistOperation("delete", "aapl", null)
        )

        val decoded = decodePendingWatchlistOps(encodePendingWatchlistOps(operations))

        assertEquals(listOf("MSFT", "AAPL"), decoded.map { it.ticker })
        assertEquals("save", decoded.first().action)
        assertEquals("Microsoft", decoded.first().item?.name)
        assertEquals(null, decoded.last().item)
    }

    @Test
    fun pendingWatchlistCodecDropsBlankOperations() {
        val raw = """
            [
              {"action":"","ticker":"AAPL"},
              {"action":"save","ticker":""},
              {"action":"delete","ticker":"TSLA"}
            ]
        """.trimIndent()

        val decoded = decodePendingWatchlistOps(raw)

        assertEquals(1, decoded.size)
        assertEquals("TSLA", decoded.single().ticker)
    }

    @Test
    fun comparisonCodecLimitsToFourItemsAndPreservesMetrics() {
        val items = (1..5).map { index ->
            StockComparisonItem(
                id = "id-$index",
                ticker = "T$index",
                name = "기업 $index",
                market = "US",
                sector = "Tech",
                currency = "USD",
                source = "비교",
                scoreValue = index.toDouble(),
                scoreText = "$index",
                expectedReturn = index * 0.1,
                revenueGrowth = null,
                roic = null,
                grossMargin = null,
                marketCap = 1_000.0 * index,
                currentPrice = 10.0 * index,
                return1M = null,
                rankChange = index,
                weight = null,
                fcfMargin = null,
                volumeSurge = null,
                updatedAt = "2026-05-20"
            )
        }

        val decoded = decodeComparisonItems(encodeComparisonItems(items))

        assertEquals(4, decoded.size)
        assertEquals("T1", decoded.first().ticker)
        assertEquals(10.0, decoded.first().currentPrice ?: 0.0, 0.0001)
        assertEquals(1, decoded.first().rankChange)
    }

    @Test
    fun csvCodecSortsAndSkipsBlankValues() {
        val encoded = encodeCsvSet(setOf(" b ", "", "a", "a"))

        assertEquals("a,b", encoded)
        assertEquals(setOf("a", "b"), decodeCsvSet(encoded))
    }
}
