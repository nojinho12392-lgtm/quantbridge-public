package com.example.myapplication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val NEWS_REQUEST_TIMEOUT_MS = 12_000L

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val repository: NewsRepository
) : ViewModel() {
    var items by mutableStateOf<List<NewsItem>>(emptyList())
        private set
    var configured by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var source by mutableStateOf<String?>(null)
        private set
    var generatedAt by mutableStateOf<String?>(null)
        private set

    private var lastQuery: String = ""
    private var lastMarket: String = "ALL"

    fun ensureNewsLoaded(market: String = "ALL") {
        if (loading || (items.isNotEmpty() && error == null)) return
        refreshNews(query = lastQuery, market = market, force = true)
    }

    fun refreshNews(
        query: String = "",
        market: String = "ALL",
        force: Boolean = true
    ) {
        val safeMarket = market.safeNewsMarket()
        val normalizedQuery = query.trim()
        if (loading) return
        if (!force && items.isNotEmpty() && error == null && lastQuery == normalizedQuery && lastMarket == safeMarket) return

        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = withTimeout(NEWS_REQUEST_TIMEOUT_MS) {
                    repository.fetchNews(query = normalizedQuery, market = safeMarket)
                }
                items = result.items
                configured = result.configured
                source = result.source
                generatedAt = result.generatedAt
                lastQuery = normalizedQuery
                lastMarket = safeMarket
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                error = newsLoadFailureSummary(exc)
            } finally {
                loading = false
            }
        }
    }

    private fun String.safeNewsMarket(): String {
        return uppercase(Locale.US).takeIf { it in setOf("ALL", "US", "KR") } ?: "ALL"
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun newsLoadFailureSummary(error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "뉴스를 불러오는 시간이 길어지고 있습니다. 마지막 성공 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "뉴스를 불러오지 못했습니다."
        }
    }
}
