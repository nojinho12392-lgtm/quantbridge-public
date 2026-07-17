package com.qubit.quantbridge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal fun earningsCalendarValueText(item: EarningsCalendarItem): String {
    val currency = marketCurrency(item.ticker, item.market)
    val marketCap = item.marketCap
    if (marketCap != null && marketCap.isFinite()) {
        if (currency == "KRW" && marketCap >= 100_000_000.0) {
            return cap(marketCap, currency)
        }
        if (currency != "KRW" && marketCap >= 1_000_000.0) {
            return cap(marketCap, currency)
        }
    }
    return if (item.market.equals("KR", ignoreCase = true)) "국내" else "미국"
}

internal fun formatEarningsCalendarDate(date: String): String {
    return try {
        val parsed = LocalDate.parse(date)
        val weekdays = listOf("월", "화", "수", "목", "금", "토", "일")
        "${parsed.monthValue}월 ${parsed.dayOfMonth}일 (${weekdays[parsed.dayOfWeek.value - 1]})"
    } catch (_: DateTimeParseException) {
        date
    }
}

internal fun parseEarningsCalendarDate(date: String): LocalDate? {
    return try {
        LocalDate.parse(date)
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun formatEarningsCalendarMonth(date: LocalDate): String {
    return "${date.year}년 ${date.monthValue}월"
}

internal fun earningsCalendarMonthCells(month: LocalDate): List<LocalDate?> {
    val firstDay = month.withDayOfMonth(1)
    val leadingBlanks = firstDay.dayOfWeek.value % 7
    val cells = mutableListOf<LocalDate?>()
    repeat(leadingBlanks) { cells += null }
    for (day in 1..firstDay.lengthOfMonth()) {
        cells += firstDay.withDayOfMonth(day)
    }
    while (cells.size % 7 != 0) {
        cells += null
    }
    return cells
}

internal fun daysUntilText(days: Int?): String {
    return when {
        days == null -> "-"
        days == 0 -> "오늘"
        days > 0 -> "${days}일 후"
        else -> "${-days}일 전"
    }
}
