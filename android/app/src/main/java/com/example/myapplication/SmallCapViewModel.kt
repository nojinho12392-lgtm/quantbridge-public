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

private const val SMALL_CAP_REQUEST_TIMEOUT_MS = 12_000L

@HiltViewModel
class SmallCapViewModel @Inject constructor(
    private val repository: SmallCapRepository
) : ViewModel() {
    var usSmallCap by mutableStateOf<List<SmallCapStock>>(emptyList())
        private set
    var krSmallCap by mutableStateOf<List<SmallCapStock>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val automaticRefreshStamps = mutableMapOf<String, Long>()

    fun stocksFor(market: Market): List<SmallCapStock> {
        return if (market == Market.US) usSmallCap else krSmallCap
    }

    fun refreshSmallCap(
        market: Market,
        force: Boolean = false,
        automatic: Boolean = false
    ) {
        if (automatic && !shouldRunAutomaticRefresh("smallcap:${market.title}", minIntervalMs = 120_000L)) return
        if (loading) return
        if (!force && stocksFor(market).isNotEmpty()) return

        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = withTimeout(SMALL_CAP_REQUEST_TIMEOUT_MS) {
                    repository.fetchSmallCap(market)
                }
                if (market == Market.US) {
                    usSmallCap = result
                } else {
                    krSmallCap = result
                }
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                error = smallCapLoadFailureSummary("스몰캡", exc)
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

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun smallCapLoadFailureSummary(label: String, error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "$label 데이터를 불러오는 시간이 길어지고 있습니다. 마지막 성공 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "$label 데이터를 불러오지 못했습니다."
        }
    }
}
