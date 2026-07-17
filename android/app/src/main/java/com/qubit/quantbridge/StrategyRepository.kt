package com.qubit.quantbridge

import com.qubit.quantbridge.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

@Singleton
class StrategyRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchStrategy(): StrategyRepositoryResult = coroutineScope {
        val backtests = async {
            safeList("백테스트") {
                parseBacktests(api.getBacktestsRaw("us")) +
                    parseBacktests(api.getBacktestsRaw("kr")) +
                    parseBacktests(api.getSmallCapBacktestsRaw("us"), sheetOverride = "US_SmallCap_Backtest") +
                    parseBacktests(api.getSmallCapBacktestsRaw("kr"), sheetOverride = "KR_SmallCap_Backtest")
            }
        }
        val drift = async { safeList("드리프트") { parseDriftItems(api.getDriftAlertsRaw()) } }
        val industry = async { safeList("업종 랭킹") { parseIndustryItems(api.getIndustryRankingRaw(limit = 30)) } }
        val orderFlow = async { safeList("오더플로우") { parseOrderFlowItems(api.getOrderFlowRaw(limit = 30)) } }
        val risk = async {
            safeValue("포트폴리오 리스크", StrategyRiskResult()) {
                val us = parsePortfolioRisk(api.getPortfolioRiskRaw("us", limit = 30))
                val kr = parsePortfolioRisk(api.getPortfolioRiskRaw("kr", limit = 30))
                StrategyRiskResult(
                    holdings = us.holdings + kr.holdings,
                    sectors = us.sectors + kr.sectors
                )
            }
        }
        val rebalance = async {
            safeList("리밸런싱") {
                parseRebalanceOrders(api.getRebalanceOrdersRaw("us", limit = 50)) +
                    parseRebalanceOrders(api.getRebalanceOrdersRaw("kr", limit = 50))
            }
        }
        val shadow = async {
            safeValue("섀도우 평가", ShadowAttributionReport(emptyList(), emptyList())) {
                parseShadowAttribution(api.getShadowAttributionRaw(market = "ALL", limit = 50))
            }
        }

        val backtestResult = backtests.await()
        val driftResult = drift.await()
        val industryResult = industry.await()
        val orderFlowResult = orderFlow.await()
        val riskResult = risk.await()
        val rebalanceResult = rebalance.await()
        val shadowResult = shadow.await()

        StrategyRepositoryResult(
            backtests = backtestResult.value,
            driftItems = driftResult.value,
            industryItems = industryResult.value,
            orderFlowItems = orderFlowResult.value,
            riskHoldings = riskResult.value.holdings,
            riskSectors = riskResult.value.sectors,
            rebalanceOrders = rebalanceResult.value,
            shadowSummaries = shadowResult.value.summaries,
            shadowItems = shadowResult.value.items,
            errors = listOfNotNull(
                backtestResult.error,
                driftResult.error,
                industryResult.error,
                orderFlowResult.error,
                riskResult.error,
                rebalanceResult.error,
                shadowResult.error
            )
        )
    }

    private fun parseBacktests(response: JsonObject, sheetOverride: String? = null): List<BacktestSummary> {
        return response.qbObjects("summary", "summaries").map { item ->
            BacktestSummary(
                market = item.qbString("Market", "market") ?: "-",
                sheet = sheetOverride ?: item.qbString("Sheet", "sheet") ?: "-",
                periods = item.qbInt("Periods", "periods") ?: 0,
                latestDate = item.qbString("Latest_Date", "latest_date") ?: "",
                cumulativeReturn = item.qbDouble("Cumulative_Ret", "Cumulative_Return", "cumulative_return"),
                maxDrawdown = item.qbDouble("Max_Drawdown", "max_drawdown"),
                avgReturn = item.qbDouble("Avg_Return", "avg_return")
            )
        }
    }

    private fun parseDriftItems(response: JsonObject): List<DriftItem> {
        return response.qbObjects("items").mapNotNull { item ->
            val ticker = item.qbString("Ticker", "ticker") ?: return@mapNotNull null
            val name = item.qbString("Name", "name") ?: ticker
            DriftItem(
                market = item.qbString("Market", "market") ?: "-",
                ticker = ticker,
                name = displayCompanyName(name, ticker),
                status = item.qbString("Status", "Recommendation", "status", "recommendation") ?: "UNKNOWN",
                driftAbs = item.qbDouble("Drift_Abs", "drift_abs"),
                targetWeight = item.qbDouble("Target_Weight", "target_weight"),
                currentWeight = item.qbDouble("Current_Weight", "current_weight"),
                returnSinceRebal = item.qbDouble("Return_Since_Rebal", "return_since_rebal")
            )
        }
    }

    private fun parseIndustryItems(response: JsonObject): List<IndustryItem> {
        return response.qbObjects("items").map { item ->
            IndustryItem(
                rank = item.qbInt("Rank", "rank"),
                industry = item.qbString("Industry", "industry") ?: "-",
                stockCount = item.qbInt("Stock_Count", "stock_count"),
                meanReturn = item.qbDouble("Mean_Return", "mean_return"),
                breadth = item.qbDouble("Breadth", "breadth")
            )
        }
    }

    private fun parseOrderFlowItems(response: JsonObject): List<OrderFlowItem> {
        return response.qbObjects("items").mapNotNull { item ->
            val ticker = item.qbString("Ticker", "ticker") ?: return@mapNotNull null
            val name = item.qbString("Name", "name") ?: ticker
            OrderFlowItem(
                rank = item.qbInt("Rank", "rank"),
                ticker = ticker,
                name = displayCompanyName(name, ticker),
                consecutiveDays = item.qbInt("Consecutive_Days", "consecutive_days"),
                foreignNetBuy = item.qbDouble("Foreign_Net_Buy", "foreign_net_buy"),
                instNetBuy = item.qbDouble("Inst_Net_Buy", "inst_net_buy")
            )
        }
    }

    private fun parsePortfolioRisk(response: JsonObject): StrategyRiskResult {
        return StrategyRiskResult(
            holdings = response.qbObjects("holdings").mapNotNull { item ->
                val ticker = item.qbString("Ticker", "ticker") ?: return@mapNotNull null
                val name = item.qbString("Name", "name") ?: ticker
                RiskHolding(
                    market = item.qbString("Market", "market"),
                    ticker = ticker,
                    name = displayCompanyName(name, ticker),
                    sector = item.qbString("Sector", "sector"),
                    portfolioWeight = item.qbDouble("Portfolio_Weight", "portfolio_weight"),
                    assetVol = item.qbDouble("Asset_Vol", "asset_vol"),
                    riskContributionPct = item.qbDouble("Risk_Contribution_Pct", "risk_contribution_pct"),
                    weightRiskRatio = item.qbDouble("Weight_Risk_Ratio", "weight_risk_ratio")
                )
            },
            sectors = response.qbObjects("sectors").mapNotNull { item ->
                val sector = item.qbString("Sector", "sector") ?: return@mapNotNull null
                RiskSector(
                    market = item.qbString("Market", "market"),
                    sector = sector,
                    holdings = item.qbDouble("Holdings", "holdings"),
                    sectorWeight = item.qbDouble("Sector_Weight", "sector_weight"),
                    riskContributionPct = item.qbDouble(
                        "Risk_Contribution_Pct",
                        "Sector_Risk_Contribution_Pct",
                        "risk_contribution_pct"
                    )
                )
            }
        )
    }

    private fun parseRebalanceOrders(response: JsonObject): List<RebalanceOrder> {
        return response.qbObjects("orders").mapNotNull { item ->
            val ticker = item.qbString("Ticker", "ticker") ?: return@mapNotNull null
            val name = item.qbString("Name", "name") ?: ticker
            RebalanceOrder(
                market = item.qbString("Market", "market"),
                ticker = ticker,
                name = displayCompanyName(name, ticker),
                action = item.qbString("Action", "action") ?: "HOLD",
                currentWeight = item.qbDouble("Current_Weight", "current_weight"),
                targetWeight = item.qbDouble("Target_Weight", "target_weight"),
                deltaWeight = item.qbDouble("Delta_Weight", "delta_weight"),
                executableTradeValue = item.qbDouble("Executable_Trade_Value", "executable_trade_value"),
                costEstimate = item.qbDouble("Cost_Estimate", "Cost_Est", "cost_estimate")
            )
        }
    }

    private fun parseShadowAttribution(response: JsonObject): ShadowAttributionReport {
        return ShadowAttributionReport(
            summaries = response.qbObjects("summary", "summaries").map { item ->
                ShadowAttributionSummary(
                    market = item.qbString("Market", "market") ?: "-",
                    horizonTradingDays = item.qbDouble("Horizon_Trading_Days", "horizon_trading_days"),
                    actualReturn = item.qbDouble("Actual_Return", "actual_return"),
                    benchmarkReturn = item.qbDouble("Benchmark_Return", "benchmark_return"),
                    alphaActual = item.qbDouble("Alpha_Actual", "alpha_actual"),
                    hitRate = item.qbDouble("Hit_Rate", "hit_rate"),
                    scoreReturnIc = item.qbDouble("Score_Return_IC", "score_return_ic")
                )
            },
            items = response.qbObjects("items").mapNotNull { item ->
                val ticker = item.qbString("Ticker", "ticker") ?: return@mapNotNull null
                val name = item.qbString("Name", "name") ?: ticker
                ShadowAttributionItem(
                    market = item.qbString("Market", "market") ?: "-",
                    ticker = ticker,
                    name = displayCompanyName(name, ticker),
                    horizonTradingDays = item.qbDouble("Horizon_Trading_Days", "horizon_trading_days"),
                    weight = item.qbDouble("Weight", "weight"),
                    stockReturn = item.qbDouble("Stock_Return", "stock_return"),
                    benchmarkReturn = item.qbDouble("Benchmark_Return", "benchmark_return"),
                    actualContribution = item.qbDouble("Actual_Contribution", "actual_contribution"),
                    excessContribution = item.qbDouble("Excess_Contribution", "excess_contribution")
                )
            }
        )
    }

    private suspend fun <T> safeValue(
        label: String,
        fallback: T,
        block: suspend () -> T
    ): FetchResult<T> {
        return runCatching { block() }
            .fold(
                onSuccess = { FetchResult(value = it, error = null) },
                onFailure = { FetchResult(value = fallback, error = "$label: ${it.readableMessage()}") }
            )
    }

    private suspend fun <T> safeList(
        label: String,
        block: suspend () -> List<T>
    ): FetchResult<List<T>> = safeValue(label, emptyList(), block)

    private fun Throwable.readableMessage(): String {
        return localizedMessage?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }
}

data class StrategyRepositoryResult(
    val backtests: List<BacktestSummary>,
    val driftItems: List<DriftItem>,
    val industryItems: List<IndustryItem>,
    val orderFlowItems: List<OrderFlowItem>,
    val riskHoldings: List<RiskHolding>,
    val riskSectors: List<RiskSector>,
    val rebalanceOrders: List<RebalanceOrder>,
    val shadowSummaries: List<ShadowAttributionSummary>,
    val shadowItems: List<ShadowAttributionItem>,
    val errors: List<String>
)

private data class FetchResult<T>(
    val value: T,
    val error: String?
)

private data class StrategyRiskResult(
    val holdings: List<RiskHolding> = emptyList(),
    val sectors: List<RiskSector> = emptyList()
)
