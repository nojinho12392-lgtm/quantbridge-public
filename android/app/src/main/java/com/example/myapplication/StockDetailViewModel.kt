package com.example.myapplication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

private const val DETAIL_REQUEST_TIMEOUT_MS = 12_000L
private const val STOCK_DETAIL_CACHE_MS = 30 * 60 * 1000L

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    private val repository: StockDetailRepository
) : ViewModel() {
    private val detailCache = mutableMapOf<String, CachedStockDetail>()

    var detail by mutableStateOf<StockDetail?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var period by mutableStateOf(ChartPeriod.SixMonths)
        private set
    var availablePeriods by mutableStateOf(ChartPeriod.entries.toSet())
        private set

    private var activeTicker: String? = null
    private var activePeriod: ChartPeriod = ChartPeriod.SixMonths
    private var historyAnchorTicker: String? = null
    private var historyAnchorPeriod: ChartPeriod? = null
    private var historyAnchorPoints: List<PricePoint> = emptyList()

    fun resetPeriod() {
        period = ChartPeriod.SixMonths
    }

    fun updatePeriod(next: ChartPeriod) {
        if (next in availablePeriods) {
            period = next
        }
    }

    fun clear() {
        activeTicker = null
        activePeriod = ChartPeriod.SixMonths
        detail = null
        error = null
        loading = false
        clearHistoryAnchor()
        resetPeriod()
    }

    fun load(request: DetailRequest, force: Boolean = false) {
        val key = normalizedTicker(request.ticker)
        if (historyAnchorTicker != key) {
            clearHistoryAnchor(key)
        }
        if (!force && activeTicker == key && activePeriod == period && detail != null) return
        activeTicker = key
        activePeriod = period
        detail = null
        error = null
        loading = true

        viewModelScope.launch {
            try {
                detail = withTimeout(DETAIL_REQUEST_TIMEOUT_MS) {
                    stockDetail(request.ticker, period, force = force)
                }
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                error = detailFailureSummary(exc)
            } finally {
                loading = false
            }
        }
    }

    fun refreshCurrent(request: DetailRequest) {
        load(request = request, force = true)
    }

    private suspend fun stockDetail(ticker: String, period: ChartPeriod, force: Boolean = false): StockDetail {
        val key = normalizedTicker(ticker)
        val now = System.currentTimeMillis()
        val requiredPeriod = if (period.maxPoints < ChartPeriod.OneYear.maxPoints) ChartPeriod.OneYear else period
        val cached = detailCache[key]?.takeIf { now - it.loadedAt < STOCK_DETAIL_CACHE_MS }
        val cachedValuationMissing = cached?.detail?.hasValuation() == false
        val cachedHistoryShort = cached?.detail?.hasEnoughHistoryFor(requiredPeriod) == false
        if (!force && !cachedValuationMissing && !cachedHistoryShort) {
            cached
                ?.takeIf { it.period.maxPoints >= requiredPeriod.maxPoints }
                ?.let { return stableDetailFor(key, it.period, it.detail) }
        }
        val requestPeriod = if (force) {
            requiredPeriod
        } else {
            cached
                ?.period
                ?.takeIf { it.maxPoints > requiredPeriod.maxPoints }
                ?: requiredPeriod
        }
        val result = repository.fetchStock(
            ticker = ticker,
            period = requestPeriod,
            refresh = force || cachedValuationMissing || cachedHistoryShort,
            profile = false
        )
        val stableResult = stableDetailFor(key, requestPeriod, result)
        detailCache[key] = CachedStockDetail(now, stableResult.loadedPeriod, stableResult)
        return stableResult
    }

    private fun stableDetailFor(key: String, requestedPeriod: ChartPeriod, result: StockDetail): StockDetail {
        if (historyAnchorTicker != key) {
            clearHistoryAnchor(key)
        }

        val incomingSpan = historySpanDays(result.prices)
        val anchorSpan = historySpanDays(historyAnchorPoints)
        if (result.prices.isNotEmpty() && (historyAnchorPoints.isEmpty() || incomingSpan >= anchorSpan)) {
            historyAnchorTicker = key
            historyAnchorPeriod = requestedPeriod
            historyAnchorPoints = result.prices
        }

        val anchorPeriod = historyAnchorPeriod
        val stablePrices = if (historyAnchorPoints.isNotEmpty() && anchorSpan > incomingSpan) {
            historyAnchorPoints
        } else {
            result.prices
        }
        val stablePeriod = if (stablePrices == historyAnchorPoints && anchorPeriod != null) {
            anchorPeriod
        } else {
            requestedPeriod
        }

        updateAvailablePeriods(stablePeriod)
        return result.copy(prices = stablePrices, loadedPeriod = stablePeriod)
    }

    private fun updateAvailablePeriods(fetchedPeriod: ChartPeriod) {
        val next = ChartPeriod.entries
            .filter { chartPeriodIsEnabled(it, historyAnchorPoints, fetchedPeriod) }
            .toSet()
            .ifEmpty { setOf(ChartPeriod.OneMonth) }
        availablePeriods = next
        if (period !in next) {
            period = next.maxBy { it.maxPoints }
        }
    }

    private fun chartPeriodIsEnabled(
        period: ChartPeriod,
        historyPoints: List<PricePoint>,
        fetchedPeriod: ChartPeriod
    ): Boolean {
        val spanDays = historySpanDays(historyPoints).takeIf { it > 0 } ?: return true
        val requiredDays = period.minimumHistorySpanDays()
        if (spanDays >= requiredDays) return true

        val fetchedPeriodLooksComplete = spanDays >= fetchedPeriod.minimumHistorySpanDays()
        if (fetchedPeriodLooksComplete && period.maxPoints > fetchedPeriod.maxPoints) {
            return true
        }
        return false
    }

    private fun ChartPeriod.minimumHistorySpanDays(): Long {
        return when (this) {
            ChartPeriod.OneMonth -> 25L
            ChartPeriod.ThreeMonths -> 75L
            ChartPeriod.SixMonths -> 150L
            ChartPeriod.OneYear -> 300L
            ChartPeriod.ThreeYears -> 900L
            ChartPeriod.FiveYears -> 1_500L
        }
    }

    private fun historySpanDays(points: List<PricePoint>): Long {
        val first = points.firstOrNull()?.date?.let(::parseHistoryDate) ?: return 0L
        val last = points.lastOrNull()?.date?.let(::parseHistoryDate) ?: return 0L
        return ChronoUnit.DAYS.between(first, last).coerceAtLeast(0L)
    }

    private fun parseHistoryDate(value: String): LocalDate? {
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun clearHistoryAnchor(ticker: String? = null) {
        historyAnchorTicker = ticker
        historyAnchorPeriod = null
        historyAnchorPoints = emptyList()
        availablePeriods = ChartPeriod.entries.toSet()
    }

    private fun StockDetail.hasValuation(): Boolean {
        return info.forwardPe != null || info.peRatio != null || info.priceToBook != null
    }

    private fun StockDetail.hasEnoughHistoryFor(period: ChartPeriod): Boolean {
        return prices.size >= period.minimumHistoryPointsForDetailCache()
    }

    private fun ChartPeriod.minimumHistoryPointsForDetailCache(): Int {
        return when (this) {
            ChartPeriod.OneMonth -> 15
            ChartPeriod.ThreeMonths -> 45
            ChartPeriod.SixMonths -> 90
            ChartPeriod.OneYear -> 180
            ChartPeriod.ThreeYears -> 540
            ChartPeriod.FiveYears -> 900
        }
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun detailFailureSummary(error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "종목 상세 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "종목 상세를 불러오지 못했습니다."
        }
    }
}
