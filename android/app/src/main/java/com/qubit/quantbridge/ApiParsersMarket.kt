package com.qubit.quantbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun parseNewsItems(array: JSONArray): List<NewsItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val title = o.cleanString("title") ?: continue
            add(
                NewsItem(
                    id = o.cleanString("id") ?: "$i:$title",
                    title = title,
                    summary = o.cleanString("summary") ?: "",
                    source = o.cleanString("source") ?: "-",
                    url = o.cleanString("url") ?: "",
                    imageUrl = firstCleanString(o, "image_url", "urlToImage", "image", "thumbnail", "thumbnail_url") ?: "",
                    publishedAt = o.cleanString("published_at") ?: "",
                    market = o.cleanString("market") ?: "",
                    ticker = o.cleanString("ticker") ?: "",
                    kind = o.cleanString("kind") ?: "news",
                    impactLabel = o.cleanString("impact_label") ?: "neutral",
                    impactLabelKo = o.cleanString("impact_label_ko") ?: impactFallbackLabel(o.cleanString("impact_label")),
                    impactScore = o.cleanDouble("impact_score") ?: 0.0,
                    impactReason = o.cleanString("impact_reason") ?: "",
                    impactScope = o.cleanString("impact_scope") ?: "general",
                    impactHorizon = o.cleanString("impact_horizon") ?: "단기",
                    impactConfidence = o.cleanString("impact_confidence") ?: "low",
                    relatedTickers = parseStringArray(o.optJSONArray("related_tickers")),
                    relatedChangePct = o.cleanDouble("related_change_pct"),
                    relatedChangeLabel = o.cleanString("related_change_label") ?: "",
                    relatedChangeHorizon = o.cleanString("related_change_horizon") ?: ""
                )
            )
        }
    }
}

fun defaultNewsQuery(market: String): String {
    return when (market.uppercase(Locale.US)) {
        "US" -> "뉴욕증시 나스닥 S&P500 미국 주식 엔비디아 테슬라 애플"
        "KR" -> "국내증시 코스피 코스닥 삼성전자 SK하이닉스"
        else -> "증시 주식 실적 반도체 환율"
    }
}

fun List<NewsItem>.filterNewsForMarket(market: String): List<NewsItem> {
    val safeMarket = market.uppercase(Locale.US)
    val articles = filter { item ->
        item.kind.equals("external_news", ignoreCase = true) && item.url.isNotBlank()
    }
    if (safeMarket == "ALL") return articles
    return articles.filter { item -> item.market.equals(safeMarket, ignoreCase = true) }
}

internal fun String.matchesNewsMarket(market: String): Boolean {
    val text = lowercase(Locale.US)
    return when (market.uppercase(Locale.US)) {
        "US" -> {
            val excludes = listOf("삼성전자", "sk하이닉스", "코스피", "코스닥", "국내 증시", "한국 증시", "현대차")
            if (excludes.any { text.contains(it.lowercase(Locale.US)) }) return false
            val includes = listOf(
                "미국", "뉴욕증시", "미 증시", "미증시", "나스닥", "s&p", "다우", "월가",
                "엔비디아", "nvidia", "테슬라", "애플", "apple", "마이크로소프트",
                "microsoft", "알파벳", "amazon", "아마존", "연준", "fed"
            )
            includes.any { text.contains(it.lowercase(Locale.US)) }
        }
        "KR" -> {
            val includes = listOf("국내 증시", "한국 증시", "코스피", "코스닥", "삼성전자", "sk하이닉스", "현대차", "외국인", "기관", "한국거래소")
            includes.any { text.contains(it.lowercase(Locale.US)) }
        }
        else -> true
    }
}

fun parseMarketIndices(array: JSONArray): List<MarketIndexQuote> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val symbol = o.cleanString("symbol") ?: continue
            val label = o.cleanString("label") ?: symbol
            val value = o.cleanDouble("value") ?: continue
            val changePct = o.cleanDouble("change_pct") ?: continue
            add(
                MarketIndexQuote(
                    symbol = symbol,
                    label = label,
                    value = value,
                    changeAbs = o.cleanDouble("change_abs") ?: 0.0,
                    changePct = changePct,
                    updatedAt = o.cleanString("updated_at") ?: ""
                )
            )
        }
    }
}

fun MarketIndexQuote.toIndicatorQuote(): MarketIndicatorQuote {
    val region = when (symbol) {
        "^KS11", "^KQ11", "KRW=X" -> "domestic"
        else -> "overseas"
    }
    return MarketIndicatorQuote(
        symbol = symbol,
        label = label,
        category = "index_fx",
        region = region,
        value = value,
        changeAbs = changeAbs,
        changePct = changePct,
        updatedAt = updatedAt
    )
}

fun MarketIndicatorQuote.toMarketIndexQuote(): MarketIndexQuote {
    return MarketIndexQuote(
        symbol = symbol,
        label = label,
        value = value,
        changeAbs = changeAbs ?: 0.0,
        changePct = changePct ?: 0.0,
        updatedAt = updatedAt.orEmpty()
    )
}

fun parseMarketIndicators(array: JSONArray): List<MarketIndicatorQuote> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val symbol = o.cleanString("symbol") ?: continue
            val value = o.cleanDouble("value") ?: continue
            add(
                MarketIndicatorQuote(
                    symbol = symbol,
                    label = o.cleanString("label") ?: symbol,
                    category = o.cleanString("category") ?: "index_fx",
                    region = o.cleanString("region") ?: "global",
                    value = value,
                    changeAbs = o.cleanDouble("change_abs"),
                    changePct = o.cleanDouble("change_pct"),
                    updatedAt = o.cleanString("updated_at")
                )
            )
        }
    }
}

fun parseMarketIndicatorSeries(array: JSONArray): List<MarketIndicatorSeries> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val symbol = o.cleanString("symbol") ?: continue
            val pointsArray = o.optJSONArray("points") ?: JSONArray()
            val points = buildList {
                for (j in 0 until pointsArray.length()) {
                    val p = pointsArray.optJSONObject(j) ?: continue
                    val close = p.cleanDouble("close") ?: continue
                    add(MarketIndicatorPoint(p.cleanString("timestamp") ?: "", close))
                }
            }
            add(MarketIndicatorSeries(symbol, points))
        }
    }
}

fun JSONObject.matches(spec: NaverIndexSpec): Boolean {
    return listOfNotNull(
        cleanString("symbolCode"),
        cleanString("reutersCode"),
        cleanString("itemCode")
    ).any { it in spec.lookupKeys }
}

fun JSONObject.toMarketIndexQuote(spec: NaverIndexSpec): MarketIndexQuote? {
    val value = cleanDouble("closePriceRaw") ?: cleanDouble("closePrice") ?: return null
    val changePct = cleanDouble("fluctuationsRatioRaw") ?: cleanDouble("fluctuationsRatio") ?: return null
    return MarketIndexQuote(
        symbol = spec.outputSymbol,
        label = spec.label,
        value = value,
        changeAbs = cleanDouble("compareToPreviousClosePriceRaw") ?: cleanDouble("compareToPreviousClosePrice") ?: 0.0,
        changePct = changePct / 100.0,
        updatedAt = cleanString("localTradedAt") ?: ""
    )
}
