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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val SEARCH_REQUEST_TIMEOUT_MS = 12_000L

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository
) : ViewModel() {
    var searchResults by mutableStateOf<List<SearchStock>>(emptyList())
        private set
    var usScored by mutableStateOf<List<ScoredStock>>(emptyList())
        private set
    var krScored by mutableStateOf<List<ScoredStock>>(emptyList())
        private set
    var loadingModes by mutableStateOf<Set<String>>(emptySet())
        private set
    var loadedModes by mutableStateOf<Set<String>>(emptySet())
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun isExploreLoading(mode: String): Boolean = canonicalMode(mode) in loadingModes

    fun exploreErrorFor(mode: String): String? = errors[canonicalMode(mode)]

    fun loadExplore(mode: String, query: String = "", force: Boolean = false) {
        val safeMode = canonicalMode(mode)
        if (safeMode !in setOf("기업", "스코어")) return
        if (!force && safeMode in loadedModes) return
        if (safeMode in loadingModes) return

        loadingModes = loadingModes + safeMode
        errors = errors - safeMode
        viewModelScope.launch {
            try {
                when (safeMode) {
                    "기업" -> searchResults = withTimeout(SEARCH_REQUEST_TIMEOUT_MS) {
                        repository.searchUniverse(query)
                    }
                    "스코어" -> withTimeout(SEARCH_REQUEST_TIMEOUT_MS) {
                        coroutineScope {
                            val us = async { repository.fetchScored(Market.US) }
                            val kr = async { repository.fetchScored(Market.KR) }
                            usScored = us.await()
                            krScored = kr.await()
                        }
                    }
                }
                loadedModes = loadedModes + safeMode
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                if (!hasData(safeMode)) loadedModes = loadedModes - safeMode
                errors = errors + (safeMode to searchFailureSummary(safeMode, exc))
            } finally {
                loadingModes = loadingModes - safeMode
            }
        }
    }

    fun searchCompanies(query: String) {
        loadExplore("기업", query, force = true)
    }

    private fun hasData(mode: String): Boolean {
        return when (mode) {
            "기업" -> searchResults.isNotEmpty()
            "스코어" -> usScored.isNotEmpty() || krScored.isNotEmpty()
            else -> false
        }
    }

    private fun canonicalMode(mode: String): String {
        return if (mode == "추천") "스코어" else mode
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun searchFailureSummary(mode: String, error: Throwable): String {
        val label = if (mode == "기업") "기업 검색" else "스코어"
        return when (error) {
            is TimeoutCancellationException -> "$label 응답이 지연되고 있습니다. 마지막 정상 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "$label 데이터를 불러오지 못했습니다."
        }
    }
}
