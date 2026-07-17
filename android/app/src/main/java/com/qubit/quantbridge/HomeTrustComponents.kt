package com.qubit.quantbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantDanger
import com.qubit.quantbridge.ui.theme.QuantWarning

@Composable
fun HomeDataTrustCard(app: QuantAppState) {
    val latestUpdatedAt = homeLatestUpdate(app)
    val issueCount = homeDataIssueCount(app)
    val loadedSourceCount = homeLoadedSourceCount(app)
    val freshness = dataFreshnessLevel(latestUpdatedAt)
    val accent = homeTrustAccent(app, freshness, issueCount)
    val title = when {
        app.homeLoading -> "데이터 동기화 중"
        issueCount > 0 -> "데이터 갱신 필요"
        loadedSourceCount > 0 -> "데이터 정상"
        else -> "데이터 대기 중"
    }
    val detail = homeTrustDetail(app, latestUpdatedAt, issueCount, loadedSourceCount)

    QuantCard(role = QuantCardRole.Status, padding = 12.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (app.homeLoading) Icons.Filled.Refresh else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DataFreshnessBadge(freshness, compact = true)
        }
    }
}

@Composable
fun HomeSourceStatusRow(app: QuantAppState) {
    val neutralColor = MaterialTheme.colorScheme.onSurfaceVariant
    val statuses = listOf(
        sourceStatus(
            title = "주요지수",
            loading = app.marketIndicatorLoading || (app.homeLoading && app.marketIndices.isEmpty() && app.marketIndicators.isEmpty()),
            error = app.marketIndicatorError,
            count = maxOf(app.marketIndicators.size, app.marketIndices.size),
            emptyDetail = "지수 대기",
            neutralColor = neutralColor
        ),
        sourceStatus(
            title = "뉴스",
            loading = app.newsLoading || (app.homeLoading && app.newsItems.isEmpty()),
            error = app.newsError,
            count = app.newsItems.size,
            emptyDetail = "뉴스 대기",
            neutralColor = neutralColor
        ),
        opsSourceStatus(app, neutralColor)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        statuses.forEach { status ->
            HomeSourceChip(status = status, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeSourceChip(status: HomeSourceStatus, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(status.color)
                )
                Text(
                    status.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                status.value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                status.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class HomeSourceStatus(
    val title: String,
    val value: String,
    val detail: String,
    val color: Color
)

private fun sourceStatus(
    title: String,
    loading: Boolean,
    error: String?,
    count: Int,
    emptyDetail: String,
    neutralColor: Color
): HomeSourceStatus {
    return when {
        loading -> HomeSourceStatus(title, "동기화", "불러오는 중", QuantWarning)
        error != null && count == 0 -> HomeSourceStatus(title, "지연", "재시도 가능", QuantWarning)
        count > 0 -> HomeSourceStatus(title, "${count}개", "표시 가능", QuantGreen)
        else -> HomeSourceStatus(title, "대기", emptyDetail, neutralColor)
    }
}

private fun opsSourceStatus(app: QuantAppState, neutralColor: Color): HomeSourceStatus {
    val opsIssueCount = app.opsHealth?.checks
        ?.count { it.status.uppercase() !in setOf("OK", "PASS", "HEALTHY") }
        ?: 0
    val signalIssueCount = app.researchQuality?.items
        ?.count { it.status.uppercase() in setOf("FAIL", "WATCH", "INSUFFICIENT") }
        ?: 0
    return when {
        app.homeLoading && app.opsHealth == null && app.researchQuality == null ->
            HomeSourceStatus("운영", "동기화", "상태 확인 중", QuantWarning)
        app.opsHealth == null && app.researchQuality == null && app.error != null ->
            HomeSourceStatus("운영", "지연", "상태 대기", QuantWarning)
        opsIssueCount + signalIssueCount > 0 ->
            HomeSourceStatus("운영", "확인", "이슈 ${opsIssueCount + signalIssueCount}개", QuantWarning)
        app.opsHealth != null || app.researchQuality != null ->
            HomeSourceStatus("운영", "정상", "품질 확인", QuantGreen)
        else ->
            HomeSourceStatus("운영", "대기", "상태 대기", neutralColor)
    }
}

@Composable
private fun homeTrustAccent(app: QuantAppState, freshness: DataFreshnessLevel, issueCount: Int): Color {
    return when {
        app.homeLoading -> MaterialTheme.colorScheme.primary
        issueCount > 0 -> QuantWarning
        freshness == DataFreshnessLevel.Fresh -> QuantGreen
        freshness == DataFreshnessLevel.Delayed -> QuantWarning
        freshness == DataFreshnessLevel.Stale -> QuantDanger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun homeTrustDetail(app: QuantAppState, latestUpdatedAt: String?, issueCount: Int, loadedSourceCount: Int): String {
    val updated = formattedUpdateTimestamp(latestUpdatedAt)
    val research = app.opsHealth?.checks?.firstOrNull {
        it.name.contains("research", ignoreCase = true) || it.name.contains("signal", ignoreCase = true)
    }
    return when {
        research != null ->
            "Signal ${app.researchQuality?.overallStatus ?: "-"} · Research ${research.status} · 갱신 $updated"
        app.researchQuality != null ->
            "Signal ${app.researchQuality?.overallStatus ?: "-"} · 경고 ${app.researchQuality?.warningCount ?: 0} · 갱신 $updated"
        issueCount > 0 ->
            "확인 항목 ${issueCount}개 · 최근 갱신 $updated"
        else ->
            "연결 소스 ${loadedSourceCount}개 · 최근 갱신 $updated"
    }
}

private fun homeLatestUpdate(app: QuantAppState): String? {
    return buildList {
        add(homeFirstMetaValue(app.usMeta, "Generated", "Generated_At", "Last_Updated"))
        add(homeFirstMetaValue(app.krMeta, "Generated", "Generated_At", "Last_Updated"))
        add(app.macro["Generated"])
        add(app.opsHealth?.generatedAt)
        addAll(app.marketIndices.map { it.updatedAt })
        addAll(app.marketIndicators.mapNotNull { it.updatedAt })
        addAll(app.usPortfolio.mapNotNull { it.lastUpdated })
        addAll(app.krPortfolio.mapNotNull { it.lastUpdated })
        addAll(app.usSmallCap.mapNotNull { it.lastUpdated })
        addAll(app.krSmallCap.mapNotNull { it.lastUpdated })
        addAll(app.newsItems.map { it.publishedAt })
    }
        .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
        .maxOrNull()
}

private fun homeDataIssueCount(app: QuantAppState): Int {
    val loadIssues = listOf(
        app.error,
        app.marketIndicatorError,
        app.newsError
    ).count { !it.isNullOrBlank() }
    val signalIssues = app.researchQuality?.items
        ?.count { it.status.uppercase() in setOf("FAIL", "WATCH", "INSUFFICIENT") }
        ?: 0
    val opsIssues = app.opsHealth?.checks
        ?.count { it.status.uppercase() !in setOf("OK", "PASS", "HEALTHY") }
        ?: 0
    return loadIssues + signalIssues + opsIssues
}

private fun homeLoadedSourceCount(app: QuantAppState): Int {
    return listOf(
        app.usPortfolio.isNotEmpty(),
        app.krPortfolio.isNotEmpty(),
        app.usSmallCap.isNotEmpty() || app.krSmallCap.isNotEmpty(),
        app.usEarnings.isNotEmpty() || app.krEarnings.isNotEmpty(),
        app.marketIndices.isNotEmpty(),
        app.marketIndicators.isNotEmpty(),
        app.newsItems.isNotEmpty(),
        app.researchQuality != null,
        app.opsHealth != null
    ).count { it }
}

private fun homeFirstMetaValue(meta: Map<String, String>, vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        meta[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}
