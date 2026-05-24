package com.example.myapplication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val ETF_REQUEST_TIMEOUT_MS = 12_000L
private const val ETF_AUTOMATIC_REFRESH_MIN_INTERVAL_MS = 300_000L

@HiltViewModel
class EtfInsightsViewModel @Inject constructor(
    private val repository: EtfInsightsRepository
) : ViewModel() {
    var items by mutableStateOf(etfInsightUniverse)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var source by mutableStateOf("fallback")
        private set
    var updatedAt by mutableStateOf<String?>(null)
        private set
    var searchItems by mutableStateOf<List<EtfInsight>>(emptyList())
        private set
    var searchLoading by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null
    private var lastAutomaticRefreshAt = 0L

    fun refreshEtfs(force: Boolean = false, automatic: Boolean = false) {
        if (automatic && !shouldRunAutomaticRefresh()) return
        if (loading) return
        if (!force && source != "fallback" && items.isNotEmpty()) return

        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = withTimeout(ETF_REQUEST_TIMEOUT_MS) {
                    repository.fetchEtfsResult(refresh = force)
                }
                if (result.items.isNotEmpty()) {
                    items = result.items
                    source = result.source ?: "api"
                    updatedAt = result.updatedAt
                }
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                error = etfFailureSummary("ETF", exc)
                if (items.isEmpty()) {
                    items = etfInsightUniverse
                    source = "fallback"
                }
            } finally {
                loading = false
            }
        }
    }

    fun searchEtfs(query: String) {
        val clean = query.trim()
        searchJob?.cancel()
        if (clean.isBlank()) {
            clearSearch()
            return
        }

        searchLoading = true
        searchError = null
        searchJob = viewModelScope.launch {
            try {
                val result = withTimeout(ETF_REQUEST_TIMEOUT_MS) {
                    repository.fetchEtfsResult(query = clean, refresh = true)
                }
                searchItems = result.items
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                searchItems = emptyList()
                searchError = etfFailureSummary("ETF 검색", exc)
            } finally {
                searchLoading = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchItems = emptyList()
        searchError = null
        searchLoading = false
    }

    private fun shouldRunAutomaticRefresh(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAutomaticRefreshAt < ETF_AUTOMATIC_REFRESH_MIN_INTERVAL_MS) return false
        lastAutomaticRefreshAt = now
        return true
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun etfFailureSummary(label: String, error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "$label 데이터를 불러오는 시간이 길어지고 있습니다. 마지막 성공 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "$label 데이터를 불러오지 못했습니다."
        }
    }
}
