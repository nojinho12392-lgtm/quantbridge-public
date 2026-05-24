package com.example.myapplication

import com.example.myapplication.generated.models.QBNewsItemModel
import com.example.myapplication.network.QuantApiService
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchNews(
        query: String = "",
        market: String = "ALL",
        limit: Int = 40
    ): NewsRepositoryResult {
        val safeMarket = market.safeNewsMarket()
        val requestQuery = query.trim().ifBlank { defaultNewsQuery(safeMarket) }
        val response = api.getNews(query = requestQuery, market = safeMarket, limit = limit)
        val items = response.items
            .orEmpty()
            .map { it.toDomain() }
            .filterNewsForMarket(safeMarket)
        return NewsRepositoryResult(
            configured = response.configured ?: false,
            items = items,
            generatedAt = response.generatedAt,
            source = response.source
        )
    }

    private fun QBNewsItemModel.toDomain(): NewsItem {
        val safeTitle = title.trim()
        val safeTicker = ticker.orEmpty().trim()
        val safeSource = source?.takeIf { it.isNotBlank() } ?: "-"
        val safeUrl = url.orEmpty().trim()
        return NewsItem(
            id = id?.takeIf { it.isNotBlank() } ?: "$safeSource:${safeUrl.ifBlank { safeTicker }}:$safeTitle",
            title = safeTitle,
            summary = summary.orEmpty(),
            source = safeSource,
            url = safeUrl,
            imageUrl = imageUrl.orEmpty(),
            publishedAt = publishedAt.orEmpty(),
            market = market.orEmpty(),
            ticker = safeTicker,
            kind = kind ?: "news",
            impactLabel = impactLabel ?: "neutral",
            impactLabelKo = impactLabelKo ?: impactFallbackLabel(impactLabel),
            impactScore = impactScore?.takeIf(Double::isFinite) ?: 0.0,
            impactReason = impactReason.orEmpty(),
            impactScope = impactScope ?: "general",
            impactHorizon = impactHorizon ?: "단기",
            impactConfidence = impactConfidence ?: "low",
            relatedTickers = relatedTickers.orEmpty(),
            relatedChangePct = relatedChangePct?.takeIf(Double::isFinite),
            relatedChangeLabel = relatedChangeLabel.orEmpty(),
            relatedChangeHorizon = relatedChangeHorizon.orEmpty()
        )
    }

    private fun String.safeNewsMarket(): String {
        return uppercase(Locale.US).takeIf { it in setOf("ALL", "US", "KR") } ?: "ALL"
    }

    private fun impactFallbackLabel(label: String?): String {
        return when (label?.lowercase(Locale.US)) {
            "positive" -> "긍정"
            "negative" -> "부정"
            else -> "중립"
        }
    }
}

data class NewsRepositoryResult(
    val configured: Boolean,
    val items: List<NewsItem>,
    val generatedAt: String?,
    val source: String?
)
