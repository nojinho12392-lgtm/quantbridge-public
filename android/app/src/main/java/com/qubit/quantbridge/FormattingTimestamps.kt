package com.qubit.quantbridge

import java.time.LocalDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal val updateDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
internal val updateDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

enum class DataFreshnessLevel(val label: String, val detail: String) {
    Fresh("최신", "최근 데이터"),
    Delayed("지연", "갱신 지연 가능"),
    Stale("오래됨", "재확인 필요"),
    Unknown("확인 필요", "갱신 시각 없음")
}

fun formattedUpdateTimestamp(rawValue: String?): String {
    val clean = rawValue.orEmpty().trim()
    if (clean.isBlank()) return "-"

    normalizedTimestampCandidates(clean).forEach { candidate ->
        val formatted = runCatching {
            OffsetDateTime.parse(candidate)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(updateDateTimeFormatter)
        }.getOrNull()
        if (formatted != null) return formatted
    }

    extractDateTimeText(clean)?.let { return it }

    return runCatching {
        LocalDate.parse(clean, updateDateFormatter).format(updateDateFormatter)
    }.getOrElse { clean }
}

fun dataFreshnessLevel(rawValue: String?): DataFreshnessLevel {
    val instant = parsedUpdateInstant(rawValue) ?: return DataFreshnessLevel.Unknown
    val ageHours = (System.currentTimeMillis() - instant.toEpochMilli()) / 3_600_000.0
    return when {
        ageHours <= 36.0 -> DataFreshnessLevel.Fresh
        ageHours <= 96.0 -> DataFreshnessLevel.Delayed
        else -> DataFreshnessLevel.Stale
    }
}

fun parsedUpdateInstant(rawValue: String?): Instant? {
    val clean = rawValue.orEmpty().trim()
    if (clean.isBlank()) return null
    return normalizedTimestampCandidates(clean).firstNotNullOfOrNull { candidate ->
        runCatching { OffsetDateTime.parse(candidate).toInstant() }.getOrNull()
    } ?: runCatching {
        LocalDate.parse(clean.take(10), updateDateFormatter)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    }.getOrNull()
}

fun dataSourceLabel(source: String?): String {
    return when (source.orEmpty().trim().lowercase(Locale.US)) {
        "yfinance" -> "시장 API"
        "storage" -> "저장 데이터"
        "storage_snapshot" -> "부분 데이터"
        "cache" -> "앱 캐시"
        "" -> "출처 미확인"
        else -> source.orEmpty()
    }
}
