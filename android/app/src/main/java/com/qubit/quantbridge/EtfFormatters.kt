package com.qubit.quantbridge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.delay

internal fun domesticEtfLogoLines(name: String, theme: String): List<String> {
    val label = domesticEtfTheme(name, theme)
    preferredDomesticEtfLogoLines(label)?.let { return it }
    return when (label) {
        "KOSPI 200" -> listOf("코스피", "200")
        "KOSPI 200TR" -> listOf("코스피", "200TR")
        "NASDAQ 100" -> listOf("나스닥", "100")
        "S&P 500" -> listOf("S&P", "500")
        "2차전지" -> listOf("2차", "전지")
        else -> fittedDomesticEtfLogoLines(label)
    }
}

internal fun domesticEtfLogoFontSize(size: Dp, lines: List<String>) = run {
    val widest = lines.maxOfOrNull(::domesticEtfLogoWidthScore) ?: 1f
    val ratio = when {
        widest <= 2.4f -> 0.285f
        widest <= 3.2f -> 0.255f
        widest <= 4.05f -> 0.215f
        widest <= 5.0f -> 0.170f
        else -> 0.145f
    }
    (size.value * ratio).coerceAtLeast(6.5f).sp
}

internal fun domesticEtfLogoWidthScore(text: String): Float {
    var score = 0f
    text.forEach { char ->
        score += when {
            char in '\uAC00'..'\uD7A3' || char in '\u3130'..'\u318F' -> 1.0f
            char.isDigit() -> 0.56f
            char in 'A'..'Z' || char in 'a'..'z' -> 0.60f
            char == ' ' -> 0.20f
            else -> 0.38f
        }
    }
    return score.coerceAtLeast(1f)
}

internal fun preferredDomesticEtfLogoLines(label: String): List<String>? {
    val compact = label.replace(" ", "").uppercase()
    return when {
        "WTI" in compact || "원유" in compact -> listOf("WTI", "원유")
        "달러" in compact && "레버리지" in compact -> listOf("달러", "레버리지")
        "달러" in compact && "인버스" in compact -> listOf("달러", "인버스")
        "코스피200TR" in compact || "KOSPI200TR" in compact -> listOf("코스피", "200TR")
        "코스피200" in compact && "인버스" in compact -> listOf("코스피200", "인버스")
        "코스닥150" in compact && "레버리지" in compact -> listOf("코스닥150", "레버리지")
        "코스닥150" in compact && "인버스" in compact -> listOf("코스닥150", "인버스")
        "코스닥150" in compact -> listOf("코스닥", "150")
        "다우존스30" in compact -> listOf("다우존스", "30")
        "국고채3년" in compact -> listOf("국고채", "3년")
        "빅테크집중" in compact -> listOf("빅테크", "집중")
        "테크TOP10" in compact -> listOf("테크", "TOP10")
        "미디어엔터" in compact -> listOf("미디어", "엔터")
        "BBIG성장" in compact -> listOf("BBIG", "성장")
        "여행레저" in compact -> listOf("여행", "레저")
        else -> null
    }
}

internal fun fittedDomesticEtfLogoLines(label: String): List<String> {
    if (label.length <= 4) return listOf(label)
    splitTrailingNumberLogoLabel(label)?.let { return it }
    listOf("레버리지", "인버스", "선물", "채권").forEach { suffix ->
        splitLogoLabel(label, suffix)?.let { return it }
    }
    val midpoint = (label.length / 2).coerceAtLeast(2)
    return listOf(label.take(midpoint), label.drop(midpoint))
}

internal fun splitTrailingNumberLogoLabel(label: String): List<String>? {
    val match = Regex("""\d+$""").find(label) ?: return null
    if (match.range.first <= 0) return null
    val prefix = label.substring(0, match.range.first)
    val suffix = match.value
    return if (prefix.isNotBlank() && suffix.isNotBlank()) listOf(prefix, suffix) else null
}

internal fun splitLogoLabel(label: String, suffix: String): List<String>? {
    val index = label.indexOf(suffix)
    if (index <= 0) return null
    val prefix = label.substring(0, index)
    val suffixText = label.substring(index)
    return if (prefix.isNotBlank() && suffixText.isNotBlank()) listOf(prefix, suffixText) else null
}

internal fun cleanEtfSummary(summary: String): String {
    var clean = summary
    listOf("국내상장 해외 ", "국내상장 ", "미국상장 ", "국내 상장 ", "미국 상장 ").forEach { token ->
        clean = clean.replace(token, "")
    }
    clean = clean
        .replace("대표적인 해외 ETF", "대표 ETF")
        .replace("대표적인 ETF", "대표 ETF")
        .replace("해외 ETF", "ETF")
        .replace("ETF입니다.", "ETF")
        .replace("ETF입니다", "ETF")
    while ("  " in clean) {
        clean = clean.replace("  ", " ")
    }
    return clean.trim()
}

internal fun etfPriceText(value: Double, currency: String): String {
    if (!value.isFinite()) return "-"
    return if (currency == "KRW") {
        "${groupedInteger(value.toLong())}원"
    } else {
        fmtPx(value, currency)
    }
}

internal fun etfSignedPriceText(value: Double, currency: String): String {
    if (!value.isFinite()) return "-"
    val sign = if (value >= 0.0) "+" else "-"
    val absolute = kotlin.math.abs(value)
    return if (currency == "KRW") {
        "$sign${groupedInteger(absolute.toLong())}원"
    } else {
        signedPx(value, currency)
    }
}

internal fun domesticEtfTheme(name: String, theme: String): String {
    val combined = "$name $theme".replace(" ", "").uppercase()
    return when {
        "코스피200TR" in combined || "KODEX200TR" in combined -> "KOSPI 200TR"
        "인버스" in combined && ("코스피200" in combined || "KODEX200" in combined) -> "코스피200인버스"
        "레버리지" in combined && "코스닥150" in combined -> "코스닥150레버리지"
        "NASDAQ100" in combined || "나스닥100" in combined -> "NASDAQ 100"
        "S&P500" in combined -> "S&P 500"
        "2차전지" in combined -> "2차전지"
        "반도체" in combined -> "반도체"
        "KODEX200" in combined || "TIGER200" in combined || "코스피200" in combined || combined.endsWith("200") -> "KOSPI 200"
        else -> theme
            .replace("국내상장", "")
            .replace("국내", "")
            .replace("미국", "")
            .replace("ETF", "")
            .replace(" ", "")
            .ifBlank { "ETF" }
    }
}

internal fun etfAvatarColor(name: String, category: String, theme: String): Color {
    return etfIssuerColor(name) ?: etfCategoryColor(category, theme)
}

internal fun etfIssuerColor(name: String): Color? {
    val upper = name.uppercase()
    return when {
        "KODEX" in upper -> Color(0xFF0050AB)
        "TIGER" in upper -> Color(0xFFF07800)
        "ACE" in upper -> Color(0xFFD60D19)
        "SOL" in upper -> Color(0xFF0045AB)
        "RISE" in upper || "KBSTAR" in upper -> Color(0xFFFFD100)
        "HANARO" in upper -> Color(0xFF008C4C)
        "ARIRANG" in upper || "PLUS" in upper -> Color(0xFFE84118)
        "KOSEF" in upper -> Color(0xFF074A91)
        "TIMEFOLIO" in upper -> Color(0xFF612AAA)
        "WOORI" in upper -> Color(0xFF005AB8)
        "INVESCO" in upper -> Color(0xFF004580)
        "VANGUARD" in upper -> Color(0xFFAB0A1C)
        "SCHWAB" in upper -> Color(0xFF00648F)
        "ISHARES" in upper || "BLACKROCK" in upper -> Color(0xFF141926)
        "SPDR" in upper -> Color(0xFF8C1428)
        "ARK" in upper -> Color(0xFF0085BF)
        "VANECK" in upper -> Color(0xFF006BB3)
        "GLOBAL X" in upper -> Color(0xFF343A9E)
        "DIREXION" in upper -> Color(0xFFC7141F)
        "PROSHARES" in upper -> Color(0xFF0052A0)
        else -> null
    }
}

internal fun etfAvatarTextColor(name: String): Color {
    return Color.White
}

internal fun etfCategoryColor(category: String, theme: String): Color {
    if (theme.contains("반도체")) return Color(0xFF7B2398)
    return when (category) {
        "성장" -> Color(0xFF2254C7)
        "대표지수" -> Color(0xFF06696D)
        "소형주" -> Color(0xFFA34C14)
        "배당" -> Color(0xFF167A45)
        "섹터" -> Color(0xFFB43236)
        "리츠" -> Color(0xFF6B401F)
        "채권" -> Color(0xFF2E527F)
        "원자재" -> Color(0xFF946B0D)
        "테마" -> Color(0xFF6E2AA3)
        "해외지수" -> Color(0xFF4545A8)
        else -> Color(0xFF3D4A57)
    }
}

internal fun exposureColor(index: Int): Color {
    return when (index % 5) {
        0 -> Color(0xFF2E63D1)
        1 -> QuantGreen
        2 -> QuantPurple
        3 -> QuantWarning
        else -> Color(0xFF007AFF)
    }
}
