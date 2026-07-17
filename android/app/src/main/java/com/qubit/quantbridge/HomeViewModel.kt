package com.qubit.quantbridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val HOME_REQUEST_TIMEOUT_MS = 35_000L
private const val HOME_REQUEST_ATTEMPTS = 2
private const val HOME_RETRY_DELAY_MS = 1_500L

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val portfolioRepository: PortfolioRepository,
    private val smallCapRepository: SmallCapRepository,
    private val pulseRepository: PulseRepository,
    private val newsRepository: NewsRepository
) : ViewModel() {
    var usPortfolio by mutableStateOf<List<PortfolioStock>>(emptyList())
        private set
    var krPortfolio by mutableStateOf<List<PortfolioStock>>(emptyList())
        private set
    var usSmallCap by mutableStateOf<List<SmallCapStock>>(emptyList())
        private set
    var krSmallCap by mutableStateOf<List<SmallCapStock>>(emptyList())
        private set
    var usEarnings by mutableStateOf<List<EarningsStock>>(emptyList())
        private set
    var krEarnings by mutableStateOf<List<EarningsStock>>(emptyList())
        private set
    var earningsCalendar by mutableStateOf<List<EarningsCalendarItem>>(emptyList())
        private set
    var signalEvents by mutableStateOf<List<SignalEvent>>(emptyList())
        private set
    var newsItems by mutableStateOf<List<NewsItem>>(emptyList())
        private set
    var macro by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val automaticRefreshStamps = mutableMapOf<String, Long>()

    val hasDashboardData: Boolean
        get() = usPortfolio.isNotEmpty() ||
            krPortfolio.isNotEmpty() ||
            usSmallCap.isNotEmpty() ||
            krSmallCap.isNotEmpty() ||
            usEarnings.isNotEmpty() ||
            krEarnings.isNotEmpty() ||
            earningsCalendar.isNotEmpty() ||
            signalEvents.isNotEmpty() ||
            newsItems.isNotEmpty()

    fun refreshHome(force: Boolean = false) {
        if (loading) return
        if (!force && hasDashboardData) return
        loading = true
        error = null
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            try {
                coroutineScope {
                    val usPortfolioJob = async { runCatchingWithTimeout { portfolioRepository.fetchPortfolio(Market.US) } }
                    val krPortfolioJob = async { runCatchingWithTimeout { portfolioRepository.fetchPortfolio(Market.KR) } }
                    val usSmallCapJob = async { runCatchingWithTimeout { smallCapRepository.fetchSmallCap(Market.US) } }
                    val krSmallCapJob = async { runCatchingWithTimeout { smallCapRepository.fetchSmallCap(Market.KR) } }
                    val macroJob = async { runCatchingWithTimeout { pulseRepository.fetchMacro() } }
                    val usEarningsJob = async { runCatchingWithTimeout { pulseRepository.fetchEarnings(Market.US) } }
                    val krEarningsJob = async { runCatchingWithTimeout { pulseRepository.fetchEarnings(Market.KR) } }
                    val calendarJob = async { runCatchingWithTimeout { pulseRepository.fetchEarningsCalendar(refresh = force) } }
                    val eventsJob = async { runCatchingWithTimeout { pulseRepository.fetchSignalEvents() } }
                    val newsJob = async { runCatchingWithTimeout { newsRepository.fetchNews(market = "ALL") } }

                    usPortfolioJob.await().commit("US 분석", failures) {
                        usPortfolio = it.stocks
                    }
                    krPortfolioJob.await().commit("KR 분석", failures) {
                        krPortfolio = it.stocks
                    }
                    usSmallCapJob.await().commit("US 스몰캡", failures) {
                        usSmallCap = it
                    }
                    krSmallCapJob.await().commit("KR 스몰캡", failures) {
                        krSmallCap = it
                    }
                    macroJob.await().commit("매크로", failures) {
                        macro = it
                    }
                    usEarningsJob.await().commit("US 실적", failures) {
                        usEarnings = it
                    }
                    krEarningsJob.await().commit("KR 실적", failures) {
                        krEarnings = it
                    }
                    calendarJob.await().commit("실적 캘린더", failures) {
                        earningsCalendar = it
                    }
                    eventsJob.await().commit("이벤트", failures) {
                        signalEvents = it
                    }
                    newsJob.await().commit("뉴스", failures) {
                        newsItems = it.items
                    }
                }
            } finally {
                error = if (failures.isNotEmpty() && !hasDashboardData) {
                    failures.joinToString("\n")
                } else {
                    null
                }
                loading = false
            }
        }
    }

    fun refreshPortfolioPrices(automatic: Boolean = false) {
        if (automatic && !shouldRunAutomaticRefresh("home:portfolio", minIntervalMs = 120_000L)) return
        viewModelScope.launch {
            runCatchingWithTimeout { portfolioRepository.fetchPortfolio(Market.US) }
                .onSuccess { usPortfolio = it.stocks }
            runCatchingWithTimeout { portfolioRepository.fetchPortfolio(Market.KR) }
                .onSuccess { krPortfolio = it.stocks }
        }
    }

    private suspend fun <T> runCatchingWithTimeout(block: suspend () -> T): Result<T> {
        return retryingApiResult(
            timeoutMs = HOME_REQUEST_TIMEOUT_MS,
            attempts = HOME_REQUEST_ATTEMPTS,
            retryDelayMs = HOME_RETRY_DELAY_MS,
            block = block
        )
    }

    private fun <T> Result<T>.commit(
        label: String,
        failures: MutableList<String>,
        apply: (T) -> Unit
    ) {
        onSuccess { value -> apply(value) }
        onFailure { failures += homeLoadFailureSummary(label, it) }
    }

    private fun homeLoadFailureSummary(label: String, error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "$label 응답이 지연되고 있습니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "$label 데이터를 불러오지 못했습니다."
        }
    }

    private fun shouldRunAutomaticRefresh(key: String, minIntervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = automaticRefreshStamps[key] ?: 0L
        if (now - last < minIntervalMs) return false
        automaticRefreshStamps[key] = now
        return true
    }
}
