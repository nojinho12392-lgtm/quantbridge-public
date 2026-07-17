package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.util.Locale

internal fun parseRecentSearches(raw: String): List<String> {
    return raw
        .split("|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

internal fun updatedRecentSearchRaw(current: List<String>, value: String): String {
    val clean = value.trim()
    if (clean.length < SEARCH_MIN_QUERY_LENGTH) return current.joinToString("|")
    return (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) })
        .take(8)
        .joinToString("|")
}

internal fun searchResultGroup(stock: SearchStock): SearchResultGroup {
    val ticker = stock.ticker.uppercase(Locale.US)
    val sector = stock.sector.orEmpty().uppercase(Locale.US)
    return when {
        sector.startsWith("ETF") -> SearchResultGroup.Etf
        ticker.startsWith("^") || ticker.endsWith("=F") || ticker.endsWith("=X") -> SearchResultGroup.Indicator
        sector.isNotBlank() -> SearchResultGroup.Company
        else -> SearchResultGroup.Other
    }
}

internal fun searchEmptyMessage(query: String): String {
    return if (query.isBlank()) {
        "현재 선택한 시장과 모드에 일치하는 데이터가 없습니다. 큐빗은 모든 종목을 얕게 보여주지 않고, 분석 가능한 기업만 깊게 봅니다."
    } else {
        "\"$query\"는 아직 큐빗 커버리지 밖일 수 있습니다. 데이터 품질과 추적 기준을 통과한 기업부터 먼저 보여줍니다."
    }
}

internal fun searchStatus(stock: SearchStock): String {
    return when {
        stock.inPortfolio && stock.inSmallCap -> "포트+스몰"
        stock.inPortfolio -> "포트"
        stock.inSmallCap -> "스몰"
        else -> "-"
    }
}

internal fun scoredKpis(stock: ScoredStock): List<Pair<String, String>> {
    return listOfNotNull(
        stock.valueScore?.let { "V" to num(it) },
        stock.qualityScore?.let { "Q" to num(it) },
        stock.momentumScore?.let { "M" to num(it) },
        stock.mlScore?.let { "ML" to num(it) },
        stock.investabilityScore?.let { "투자" to num(it) }
    ).ifEmpty {
        listOfNotNull(
            stock.roic?.let { "ROIC" to pct(it, signed = false) },
            stock.revGrowth?.let { "성장" to pct(it) },
            stock.grossMargin?.let { "마진" to pct(it, signed = false) }
        )
    }
}

internal fun searchDetail(stock: SearchStock): DetailRequest {
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = stock.currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "검색 정보",
                listOf(
                    DetailMetric("시장", stock.market ?: "-"),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-"),
                    DetailMetric("시가총액", cap(stock.marketCap, stock.currency)),
                    DetailMetric("상태", searchStatus(stock), if (stock.inPortfolio || stock.inSmallCap) DetailTone.Primary else DetailTone.Neutral)
                )
            )
        ),
        signals = listOfNotNull(
            if (stock.inPortfolio) DetailSignal("분석 상위군 포함", "현재 모델 분석 상위군에 포함된 종목입니다.", DetailTone.Primary) else null,
            if (stock.inSmallCap) DetailSignal("스몰캡 후보", "스몰캡 리스트에 포함된 후보입니다.", DetailTone.Positive) else null
        ).ifEmpty {
            listOf(DetailSignal("전체 유니버스 종목", "아직 분석 상위군이나 스몰캡 후보는 아니지만, 차트와 기업 정보를 확인할 수 있습니다.", DetailTone.Neutral))
        },
        factors = emptyList()
    )
}
