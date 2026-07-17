package com.qubit.quantbridge

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

private const val PULSE_REQUEST_TIMEOUT_MS = 12_000L

@HiltViewModel
class PulseViewModel @Inject constructor(
    private val repository: PulseRepository
) : ViewModel() {
    var macro by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var usEarnings by mutableStateOf<List<EarningsStock>>(emptyList())
        private set
    var krEarnings by mutableStateOf<List<EarningsStock>>(emptyList())
        private set
    var earningsCalendar by mutableStateOf<List<EarningsCalendarItem>>(emptyList())
        private set
    var signalEvents by mutableStateOf<List<SignalEvent>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun earningsFor(market: Market): List<EarningsStock> {
        return if (market == Market.US) usEarnings else krEarnings
    }

    fun refreshPulse(force: Boolean = false) {
        if (loading) return
        if (!force && macro.isNotEmpty() && (usEarnings.isNotEmpty() || krEarnings.isNotEmpty()) && earningsCalendar.isNotEmpty()) {
            return
        }

        loading = true
        error = null
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            runCatchingWithTimeout { repository.fetchMacro() }
                .onSuccess { macro = it }
                .onFailure { failures += pulseLoadFailureSummary("매크로", it) }
            runCatchingWithTimeout { repository.fetchEarnings(Market.US) }
                .onSuccess { usEarnings = it }
                .onFailure { failures += pulseLoadFailureSummary("US 실적", it) }
            runCatchingWithTimeout { repository.fetchEarnings(Market.KR) }
                .onSuccess { krEarnings = it }
                .onFailure { failures += pulseLoadFailureSummary("KR 실적", it) }
            runCatchingWithTimeout { repository.fetchEarningsCalendar(refresh = force) }
                .onSuccess { earningsCalendar = it }
                .onFailure { failures += pulseLoadFailureSummary("실적 캘린더", it) }
            runCatchingWithTimeout { repository.fetchSignalEvents() }
                .onSuccess { signalEvents = it }
                .onFailure { failures += pulseLoadFailureSummary("이벤트", it) }

            if (failures.isNotEmpty()) {
                error = if (macro.isEmpty() && usEarnings.isEmpty() && krEarnings.isEmpty() && earningsCalendar.isEmpty()) {
                    failures.joinToString("\n")
                } else {
                    "일부 데이터 지연: ${failures.joinToString(" · ")}"
                }
            }
            loading = false
        }
    }

    fun ensureEarningsCalendarLoaded() {
        if (!earningsCalendarNeedsRefresh(earningsCalendar) || loading) return
        loading = true
        error = null
        viewModelScope.launch {
            runCatchingWithTimeout { repository.fetchEarningsCalendar(refresh = false) }
                .onSuccess {
                    earningsCalendar = it
                    if (it.isNotEmpty()) error = null
                }
                .onFailure {
                    error = if (macro.isEmpty() && usEarnings.isEmpty() && krEarnings.isEmpty()) {
                        pulseLoadFailureSummary("실적 캘린더", it)
                    } else {
                        "일부 데이터 지연: ${pulseLoadFailureSummary("실적 캘린더", it)}"
                    }
                }
            loading = false
        }
    }

    private suspend fun <T> runCatchingWithTimeout(block: suspend () -> T): Result<T> {
        return runCatching { withTimeout(PULSE_REQUEST_TIMEOUT_MS) { block() } }
            .onFailure { it.throwIfCancellation() }
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun pulseLoadFailureSummary(label: String, error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "$label 데이터를 불러오는 시간이 길어지고 있습니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "$label 데이터를 불러오지 못했습니다."
        }
    }

    private fun earningsCalendarNeedsRefresh(items: List<EarningsCalendarItem>): Boolean {
        if (items.isEmpty()) return true
        if (items.size < 100) return false
        val repeatedBucketCount = items
            .groupBy { it.nextEarningsDate }
            .values
            .mapNotNull { dayItems ->
                val tickers = dayItems
                    .map { it.ticker.trim().uppercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()
                if (tickers.size >= 10) tickers.joinToString("|") else null
            }
            .groupingBy { it }
            .eachCount()
            .values
            .maxOrNull() ?: 0
        return repeatedBucketCount >= 4
    }
}
