package com.qubit.quantbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun parseQualityGates(array: JSONArray): List<QualityGate> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                QualityGate(
                    market = o.cleanString("Market") ?: "-",
                    factor = o.cleanString("Factor") ?: o.cleanString("Signal") ?: "-",
                    status = o.cleanString("Status") ?: "UNKNOWN",
                    meanIc = o.cleanDouble("Mean_IC"),
                    positiveRate = o.cleanDouble("Positive_IC_Rate"),
                    snapshots = o.cleanDouble("Snapshots"),
                    evidenceSource = o.cleanString("Evidence_Source"),
                    productionReady = o.cleanString("Production_Ready")
                )
            )
        }
    }
}

fun parseMLBlendItems(array: JSONArray): List<MLBlendItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(parseMLBlendItem(o))
        }
    }
}

fun parseMLBlendItem(o: JSONObject): MLBlendItem {
    return MLBlendItem(
        generated = o.cleanString("Generated") ?: "",
        market = o.cleanString("Market") ?: "-",
        model = o.cleanString("Model") ?: "-",
        rankIc = o.cleanDouble("Rank_IC"),
        mlWeight = o.cleanDouble("ML_Weight"),
        factorWeight = o.cleanDouble("Factor_Weight"),
        mlWeightReason = o.cleanString("ML_Weight_Reason"),
        factorScoreColumn = o.cleanString("Factor_Score_Column"),
        mlFactorSpearman = o.cleanDouble("ML_Factor_Spearman"),
        mlFactorPearson = o.cleanDouble("ML_Factor_Pearson"),
        predictedStocks = o.cleanDouble("Predicted_Stocks"),
        top5 = o.cleanString("Top5"),
        notes = o.cleanString("Notes"),
        status = o.cleanString("Status")
    )
}

fun parseOpsChecks(array: JSONArray): List<OpsCheck> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                OpsCheck(
                    name = o.cleanString("name") ?: "-",
                    status = o.cleanString("status") ?: "UNKNOWN",
                    message = o.cleanString("message") ?: ""
                )
            )
        }
    }
}

fun parseBacktestSummary(o: JSONObject): BacktestSummary {
    return BacktestSummary(
        market = o.cleanString("Market") ?: "-",
        sheet = o.cleanString("Sheet") ?: "-",
        periods = o.cleanInt("Periods") ?: 0,
        latestDate = o.cleanString("Latest_Date") ?: "",
        cumulativeReturn = o.cleanDouble("Cumulative_Ret"),
        maxDrawdown = o.cleanDouble("Max_Drawdown"),
        avgReturn = o.cleanDouble("Avg_Return")
    )
}

fun parseDriftItems(array: JSONArray): List<DriftItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                DriftItem(
                    market = o.cleanString("Market") ?: "-",
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    status = o.cleanString("Status") ?: o.cleanString("Recommendation") ?: "UNKNOWN",
                    driftAbs = o.cleanDouble("Drift_Abs"),
                    targetWeight = o.cleanDouble("Target_Weight"),
                    currentWeight = o.cleanDouble("Current_Weight"),
                    returnSinceRebal = o.cleanDouble("Return_Since_Rebal")
                )
            )
        }
    }
}

fun parseIndustryItems(array: JSONArray): List<IndustryItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                IndustryItem(
                    rank = o.cleanInt("Rank"),
                    industry = o.cleanString("Industry") ?: "-",
                    stockCount = o.cleanInt("Stock_Count"),
                    meanReturn = o.cleanDouble("Mean_Return"),
                    breadth = o.cleanDouble("Breadth")
                )
            )
        }
    }
}

fun parseOrderFlowItems(array: JSONArray): List<OrderFlowItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                OrderFlowItem(
                    rank = o.cleanInt("Rank"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    consecutiveDays = o.cleanInt("Consecutive_Days"),
                    foreignNetBuy = o.cleanDouble("Foreign_Net_Buy"),
                    instNetBuy = o.cleanDouble("Inst_Net_Buy")
                )
            )
        }
    }
}

fun parseRiskHoldings(array: JSONArray): List<RiskHolding> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                RiskHolding(
                    market = o.cleanString("Market"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    sector = o.cleanString("Sector"),
                    portfolioWeight = o.cleanDouble("Portfolio_Weight"),
                    assetVol = o.cleanDouble("Asset_Vol"),
                    riskContributionPct = o.cleanDouble("Risk_Contribution_Pct"),
                    weightRiskRatio = o.cleanDouble("Weight_Risk_Ratio")
                )
            )
        }
    }
}

fun parseRiskSectors(array: JSONArray): List<RiskSector> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val sector = o.cleanString("Sector") ?: continue
            add(
                RiskSector(
                    market = o.cleanString("Market"),
                    sector = sector,
                    holdings = o.cleanDouble("Holdings"),
                    sectorWeight = o.cleanDouble("Sector_Weight"),
                    riskContributionPct = o.cleanDouble("Sector_Risk_Contribution_Pct")
                )
            )
        }
    }
}

fun parseRebalanceOrders(array: JSONArray): List<RebalanceOrder> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                RebalanceOrder(
                    market = o.cleanString("Market"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    action = o.cleanString("Action") ?: "HOLD",
                    currentWeight = o.cleanDouble("Current_Weight"),
                    targetWeight = o.cleanDouble("Target_Weight"),
                    deltaWeight = o.cleanDouble("Delta_Weight"),
                    executableTradeValue = o.cleanDouble("Executable_Trade_Value"),
                    costEstimate = o.cleanDouble("Cost_Est")
                )
            )
        }
    }
}

fun parseShadowAttributionSummaries(array: JSONArray): List<ShadowAttributionSummary> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                ShadowAttributionSummary(
                    market = o.cleanString("Market") ?: "-",
                    horizonTradingDays = o.cleanDouble("Horizon_Trading_Days"),
                    actualReturn = o.cleanDouble("Actual_Return"),
                    benchmarkReturn = o.cleanDouble("Benchmark_Return"),
                    alphaActual = o.cleanDouble("Alpha_Actual"),
                    hitRate = o.cleanDouble("Hit_Rate"),
                    scoreReturnIc = o.cleanDouble("Score_Return_IC")
                )
            )
        }
    }
}

fun parseShadowAttributionItems(array: JSONArray): List<ShadowAttributionItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                ShadowAttributionItem(
                    market = o.cleanString("Market") ?: "-",
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    horizonTradingDays = o.cleanDouble("Horizon_Trading_Days"),
                    weight = o.cleanDouble("Weight"),
                    stockReturn = o.cleanDouble("Stock_Return"),
                    benchmarkReturn = o.cleanDouble("Benchmark_Return"),
                    actualContribution = o.cleanDouble("Actual_Contribution"),
                    excessContribution = o.cleanDouble("Excess_Contribution")
                )
            )
        }
    }
}
