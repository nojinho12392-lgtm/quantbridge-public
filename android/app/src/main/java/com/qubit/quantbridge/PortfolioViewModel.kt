package com.qubit.quantbridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

private const val PORTFOLIO_REQUEST_TIMEOUT_MS = 30_000L
private const val PORTFOLIO_REQUEST_ATTEMPTS = 2

@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val repository: PortfolioRepository
) : ViewModel() {
    var usMeta by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var krMeta by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var usPortfolio by mutableStateOf<List<PortfolioStock>>(emptyList())
        private set
    var krPortfolio by mutableStateOf<List<PortfolioStock>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val automaticRefreshStamps = mutableMapOf<String, Long>()

    fun stocksFor(market: Market): List<PortfolioStock> {
        return if (market == Market.US) usPortfolio else krPortfolio
    }

    fun refreshPortfolio(
        market: Market,
        force: Boolean = false,
        automatic: Boolean = false
    ) {
        if (automatic && !shouldRunAutomaticRefresh("portfolio:${market.title}", minIntervalMs = 120_000L)) return
        if (loading) return
        if (!force && stocksFor(market).isNotEmpty()) return

        loading = true
        error = null
        viewModelScope.launch {
            try {
                retryingApiResult(
                    timeoutMs = PORTFOLIO_REQUEST_TIMEOUT_MS,
                    attempts = PORTFOLIO_REQUEST_ATTEMPTS
                ) {
                    repository.fetchPortfolio(market)
                }.onSuccess { result ->
                    if (market == Market.US) {
                        usMeta = result.meta
                        usPortfolio = result.stocks
                    } else {
                        krMeta = result.meta
                        krPortfolio = result.stocks
                    }
                }.onFailure { exc ->
                    error = portfolioLoadFailureSummary("분석", exc)
                }
            } finally {
                loading = false
            }
        }
    }

    private fun shouldRunAutomaticRefresh(key: String, minIntervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = automaticRefreshStamps[key] ?: 0L
        if (now - last < minIntervalMs) return false
        automaticRefreshStamps[key] = now
        return true
    }

    private fun portfolioLoadFailureSummary(label: String, error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "$label 데이터를 불러오는 시간이 길어지고 있습니다. 마지막 성공 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "$label 데이터를 불러오지 못했습니다."
        }
    }
}
