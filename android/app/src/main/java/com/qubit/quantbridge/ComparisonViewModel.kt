package com.qubit.quantbridge

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ComparisonViewModel @Inject constructor(
    private val repository: ComparisonRepository,
    private val userPreferences: UserPreferencesRepository
) : ViewModel() {
    val items = mutableStateListOf<StockComparisonItem>()
    var recommendationCache by mutableStateOf<Map<String, List<StockComparisonItem>>>(emptyMap())
        private set
    var showSheet by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            items.clear()
            items.addAll(userPreferences.comparisonItemsSnapshot().take(MAX_COMPARISON_ITEM_COUNT))
        }
    }

    fun contains(item: StockComparisonItem): Boolean {
        return items.any { it.id == item.id || normalizedTicker(it.ticker) == normalizedTicker(item.ticker) }
    }

    fun add(item: StockComparisonItem) {
        if (contains(item)) return
        if (items.size >= MAX_COMPARISON_ITEM_COUNT) items.removeAt(0)
        items.add(item)
        save()
    }

    fun remove(item: StockComparisonItem) {
        val key = normalizedTicker(item.ticker)
        items.removeAll { it.id == item.id || normalizedTicker(it.ticker) == key }
        save()
    }

    fun replace(nextItems: List<StockComparisonItem>) {
        items.clear()
        items.addAll(nextItems.distinctBy { it.id }.take(MAX_COMPARISON_ITEM_COUNT))
        save()
    }

    fun clear() {
        items.clear()
        save()
    }

    fun recommendationsFor(anchor: StockComparisonItem): List<StockComparisonItem> {
        return recommendationCache[normalizedTicker(anchor.ticker)].orEmpty()
    }

    fun refreshRecommendations(anchor: StockComparisonItem) {
        val key = normalizedTicker(anchor.ticker)
        if (recommendationCache[key]?.isNotEmpty() == true) return
        viewModelScope.launch {
            runCatching { repository.fetchRecommendations(anchor) }
                .onSuccess { recommendations ->
                    recommendationCache = recommendationCache + (key to recommendations)
                }
        }
    }

    fun openSheet() {
        showSheet = true
    }

    fun closeSheet() {
        showSheet = false
    }

    private fun save() {
        val snapshot = items.toList()
        viewModelScope.launch {
            userPreferences.setComparisonItems(snapshot)
        }
    }

    private companion object {
        const val MAX_COMPARISON_ITEM_COUNT = 4
    }
}
