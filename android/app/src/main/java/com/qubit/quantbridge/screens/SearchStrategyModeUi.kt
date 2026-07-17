package com.qubit.quantbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListScope
import kotlinx.coroutines.CoroutineScope

internal fun LazyListScope.searchStrategyModeItems(
    strategyViewModel: StrategyViewModel,
    riskHoldingRows: List<RiskHolding>,
    riskSectorRows: List<RiskSector>,
    rebalanceRows: List<RebalanceOrder>,
    shadowSummaryRows: List<ShadowAttributionSummary>,
    shadowItemRows: List<ShadowAttributionItem>
) {
    item {
        HeaderCard(
            title = "백테스트",
            value = "${strategyViewModel.backtests.size}개",
            subtitle = "미국/국내 분석 상위군과 스몰캡 백테스트 요약",
            trailing = if (strategyViewModel.loading) "동기화" else "성과"
        )
    }
    if (strategyViewModel.backtests.isEmpty()) {
        item { EmptyCard("백테스트 없음", "백테스트 시트 또는 저장소 데이터가 아직 없습니다.") }
    } else {
        items(strategyViewModel.backtests, key = { it.sheet }) { summary ->
            StatusRow(
                title = backtestTitle(summary),
                status = summary.market,
                subtitle = "누적 ${pct(summary.cumulativeReturn)} · MDD ${pct(summary.maxDrawdown)} · 기간 ${summary.periods} · ${summary.latestDate.ifBlank { "-" }}"
            )
        }
    }

    item { SectionTitle("Rebalance Drift", "${strategyViewModel.driftItems.size}") }
    if (strategyViewModel.driftItems.isEmpty()) {
        item { EmptyCard("드리프트 없음", "Portfolio_Drift_Alert 데이터가 아직 없습니다.") }
    } else {
        items(strategyViewModel.driftItems.take(20), key = { "${it.market}:${it.ticker}" }) { item ->
            StatusRow(
                title = "${item.market} ${item.name}",
                status = item.status,
                subtitle = "${item.ticker} · 드리프트 ${pct(item.driftAbs, signed = false)} · 목표 ${pct(item.targetWeight, signed = false)} → 현재 ${pct(item.currentWeight, signed = false)}"
            )
        }
    }

    item { SectionTitle("Portfolio Risk", "${riskHoldingRows.size}") }
    if (riskHoldingRows.isEmpty()) {
        item { EmptyCard("리스크 기여도 없음", "Final_Portfolio_Risk 데이터가 아직 없습니다.") }
    } else {
        items(riskHoldingRows.take(20), key = { "${it.market}:${it.ticker}" }) { item ->
            StatusRow(
                title = "${item.market ?: "-"} ${item.name}",
                status = pct(item.riskContributionPct, signed = false),
                subtitle = "${item.ticker} · 비중 ${pct(item.portfolioWeight, signed = false)} · 변동성 ${pct(item.assetVol, signed = false)} · W/R ${num(item.weightRiskRatio)}"
            )
        }
    }

    item { SectionTitle("Sector Risk", "${riskSectorRows.size}") }
    if (riskSectorRows.isEmpty()) {
        item { EmptyCard("섹터 리스크 없음", "섹터별 리스크 기여도 데이터가 아직 없습니다.") }
    } else {
        items(riskSectorRows.take(12), key = { "${it.market}:${it.sector}" }) { item ->
            StatusRow(
                title = "${item.market ?: "-"} ${item.sector}",
                status = pct(item.riskContributionPct, signed = false),
                subtitle = "비중 ${pct(item.sectorWeight, signed = false)} · 보유 ${item.holdings?.toInt() ?: 0}개"
            )
        }
    }

    item { SectionTitle("Rebalance Orders", "${rebalanceRows.size}") }
    if (rebalanceRows.isEmpty()) {
        item { EmptyCard("실행 주문 없음", "리밸런싱 주문 데이터가 아직 없습니다.") }
    } else {
        items(rebalanceRows.take(20), key = { "${it.market}:${it.ticker}:${it.action}" }) { item ->
            StatusRow(
                title = "${item.market ?: "-"} ${item.name}",
                status = item.action,
                subtitle = "${item.ticker} · ${rebalanceTradeText(item)} · 목표 ${pct(item.targetWeight, signed = false)} · 비용 ${compactNumber(item.costEstimate)}"
            )
        }
    }

    item { SectionTitle("Shadow Attribution", "${shadowSummaryRows.size}") }
    if (shadowSummaryRows.isEmpty()) {
        item { EmptyCard("섀도우 평가 없음", "Shadow_Portfolio_Attribution 데이터가 아직 없습니다.") }
    } else {
        items(shadowSummaryRows.take(6), key = { "${it.market}:${it.horizonTradingDays}" }) { item ->
            StatusRow(
                title = "${item.market} ${horizonLabel(item.horizonTradingDays)}",
                status = pct(item.alphaActual),
                subtitle = "실제 ${pct(item.actualReturn)} · BM ${pct(item.benchmarkReturn)} · 적중률 ${pct(item.hitRate, signed = false)} · IC ${num(item.scoreReturnIc)}"
            )
        }
    }

    item { SectionTitle("Shadow Contributors", "${shadowItemRows.size}") }
    if (shadowItemRows.isEmpty()) {
        item { EmptyCard("종목별 귀속 없음", "종목별 섀도우 성과 귀속 데이터가 아직 없습니다.") }
    } else {
        items(shadowItemRows.take(15), key = { "${it.market}:${it.ticker}:${it.horizonTradingDays}" }) { item ->
            StatusRow(
                title = "${item.market} ${item.name}",
                status = pct(item.actualContribution),
                subtitle = "${item.ticker} · ${horizonLabel(item.horizonTradingDays)} · 수익률 ${pct(item.stockReturn)} · BM ${pct(item.benchmarkReturn)} · 초과 ${pct(item.excessContribution)}"
            )
        }
    }

    item { SectionTitle("US Industry Ranking", "${strategyViewModel.industryItems.size}") }
    if (strategyViewModel.industryItems.isEmpty()) {
        item { EmptyCard("업종 랭킹 없음", "US_Industry_Ranking 데이터가 아직 없습니다.") }
    } else {
        items(strategyViewModel.industryItems.take(15), key = { "${it.rank}:${it.industry}" }) { item ->
            StatusRow(
                title = "#${item.rank ?: "-"} ${item.industry}",
                status = "Rank",
                subtitle = "종목 ${item.stockCount ?: 0} · 평균수익 ${pct(item.meanReturn)} · Breadth ${pct(item.breadth, signed = false)}"
            )
        }
    }

    item { SectionTitle("KR Order Flow", "${strategyViewModel.orderFlowItems.size}") }
    if (strategyViewModel.orderFlowItems.isEmpty()) {
        item { EmptyCard("오더플로우 없음", "KR_Dual_Net_Buyers 데이터가 아직 없습니다.") }
    } else {
        items(strategyViewModel.orderFlowItems.take(15), key = { it.ticker }) { item ->
            StatusRow(
                title = "#${item.rank ?: "-"} ${item.name}",
                status = "${item.consecutiveDays ?: 0}일",
                subtitle = "${item.ticker} · 외국인 ${compactNumber(item.foreignNetBuy)} · 기관 ${compactNumber(item.instNetBuy)}"
            )
        }
    }
}
