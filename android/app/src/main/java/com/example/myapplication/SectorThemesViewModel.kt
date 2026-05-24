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

private const val SECTOR_REQUEST_TIMEOUT_MS = 12_000L
private const val SECTOR_SUMMARY_MEMBERS = 12
private const val SECTOR_DETAIL_MEMBERS = 80
private const val SECTOR_DETAIL_PREFETCH_COUNT = 8

@HiltViewModel
class SectorThemesViewModel @Inject constructor(
    private val repository: SectorThemesRepository
) : ViewModel() {
    var themes by mutableStateOf<List<SectorTheme>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var market by mutableStateOf("ALL")
        private set
    var source by mutableStateOf<String?>(null)
        private set
    var generatedAt by mutableStateOf<String?>(null)
        private set
    var selectedTheme by mutableStateOf<SectorTheme?>(null)
        private set
    var selectedThemeLoading by mutableStateOf(false)
        private set
    var selectedThemeError by mutableStateOf<String?>(null)
        private set

    private val automaticRefreshStamps = mutableMapOf<String, Long>()
    private val detailCache = mutableMapOf<String, SectorTheme>()
    private val detailPrefetching = mutableSetOf<String>()
    private var selectedThemeRequestId = 0

    fun refreshSectorThemes(
        market: String = "ALL",
        force: Boolean = false,
        reloadExisting: Boolean = false,
        automatic: Boolean = false
    ) {
        val safeMarket = market.safeSectorMarket()
        if (automatic && !shouldRunAutomaticRefresh("sectors:$safeMarket", minIntervalMs = 120_000L)) return
        if (loading) return
        if (!force && !reloadExisting && this.market == safeMarket && themes.isNotEmpty()) return

        loading = themes.isEmpty() || market != safeMarket
        viewModelScope.launch {
            var hasVisibleData = this@SectorThemesViewModel.market == safeMarket && themes.isNotEmpty()
            try {
                if (!force) {
                    repository.cachedSectorThemesResult(safeMarket)?.let { cached ->
                        applySectorThemesResult(cached)
                        hasVisibleData = true
                        loading = false
                        seedDetailCache(cached.items)
                    }
                }
                val result = withTimeout(SECTOR_REQUEST_TIMEOUT_MS) {
                    repository.fetchSectorThemesResult(
                        market = safeMarket,
                        members = SECTOR_SUMMARY_MEMBERS,
                        refresh = force
                    )
                }
                applySectorThemesResult(result)
                seedDetailCache(result.items)
                prefetchThemeDetails(result.items.take(SECTOR_DETAIL_PREFETCH_COUNT))
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                if (!hasVisibleData) {
                    error = sectorLoadFailureSummary("섹터", exc)
                }
            } finally {
                loading = false
            }
        }
    }

    fun openTheme(theme: SectorTheme, force: Boolean = false) {
        val key = theme.detailCacheKey()
        val cached = if (force) null else detailCache[key]
        selectedTheme = cached ?: theme
        selectedThemeLoading = cached == null || force
        selectedThemeError = null
        val requestId = ++selectedThemeRequestId
        viewModelScope.launch {
            try {
                if (cached == null && !force) {
                    repository.cachedSectorThemeDetail(
                        label = theme.label,
                        market = theme.market
                    )?.let { diskCached ->
                        if (requestId == selectedThemeRequestId && selectedTheme?.detailCacheKey() == key) {
                            detailCache[key] = diskCached
                            selectedTheme = diskCached
                            selectedThemeLoading = false
                            selectedThemeError = null
                        }
                    }
                }
                val detail = withTimeout(SECTOR_REQUEST_TIMEOUT_MS) {
                    repository.fetchSectorThemeDetail(
                        label = theme.label,
                        market = theme.market,
                        members = SECTOR_DETAIL_MEMBERS,
                        refresh = force
                    )
                } ?: theme
                if (requestId == selectedThemeRequestId && selectedTheme?.detailCacheKey() == key) {
                    detailCache[key] = detail
                    selectedTheme = detail
                    selectedThemeError = null
                }
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                if (requestId == selectedThemeRequestId && selectedTheme?.detailCacheKey() == key) {
                    selectedThemeError = sectorLoadFailureSummary(theme.label, exc)
                }
            } finally {
                if (requestId == selectedThemeRequestId && selectedTheme?.detailCacheKey() == key) {
                    selectedThemeLoading = false
                }
            }
        }
    }

    fun prefetchThemeDetails(themes: List<SectorTheme>) {
        themes.forEach { theme ->
            val key = theme.detailCacheKey()
            if (detailCache[key]?.members?.isNotEmpty() == true || key in detailPrefetching) return@forEach
            detailPrefetching += key
            viewModelScope.launch {
                try {
                    val detail = withTimeout(SECTOR_REQUEST_TIMEOUT_MS) {
                        repository.fetchSectorThemeDetail(
                            label = theme.label,
                            market = theme.market,
                            members = SECTOR_DETAIL_MEMBERS,
                            refresh = false
                        )
                    }
                    if (detail != null) {
                        detailCache[key] = detail
                        if (selectedTheme?.detailCacheKey() == key) {
                            selectedTheme = detail
                            selectedThemeError = null
                        }
                    }
                } catch (exc: Exception) {
                    exc.throwIfCancellation()
                } finally {
                    detailPrefetching -= key
                }
            }
        }
    }

    private fun seedDetailCache(themes: List<SectorTheme>) {
        themes.forEach { theme ->
            if (theme.members.isNotEmpty()) {
                detailCache.putIfAbsent(theme.detailCacheKey(), theme)
            }
        }
    }

    private fun applySectorThemesResult(result: SectorThemesResult) {
        themes = result.items
        market = result.market.safeSectorMarket()
        source = result.source
        generatedAt = result.generatedAt
        error = null
    }

    fun dismissSelectedTheme() {
        selectedThemeRequestId += 1
        selectedTheme = null
        selectedThemeLoading = false
        selectedThemeError = null
    }

    private fun shouldRunAutomaticRefresh(key: String, minIntervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = automaticRefreshStamps[key] ?: 0L
        if (now - last < minIntervalMs) return false
        automaticRefreshStamps[key] = now
        return true
    }

    private fun String.safeSectorMarket(): String {
        return uppercase(Locale.US).takeIf { it in setOf("ALL", "US", "KR") } ?: "ALL"
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun SectorTheme.detailCacheKey(): String {
        return "${market.safeSectorMarket()}:${label.trim().lowercase(Locale.US)}"
    }

    private fun sectorLoadFailureSummary(label: String, error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "$label 데이터를 불러오는 시간이 길어지고 있습니다. 마지막 성공 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "$label 데이터를 불러오지 못했습니다."
        }
    }
}
