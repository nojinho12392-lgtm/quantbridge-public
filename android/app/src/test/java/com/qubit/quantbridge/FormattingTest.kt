package com.qubit.quantbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FormattingTest {
    @Test
    fun krCodeExtractsSixDigitCode() {
        assertEquals("005930", krCode("005930.KS"))
        assertEquals("000660", krCode("KR-000660"))
        assertEquals("", krCode("AAPL"))
    }

    @Test
    fun missingKrNameDetectsTickerLikeNames() {
        assertTrue(isMissingKrName("", "005930.KS"))
        assertTrue(isMissingKrName("005930", "005930.KS"))
        assertTrue(isMissingKrName("005930.KS", "005930.KS"))
        assertFalse(isMissingKrName("삼성전자", "005930.KS"))
    }

    @Test
    fun resolvedKrCompanyNameReturnsServerProvidedName() {
        assertEquals("005490.KS", resolvedKrCompanyName("005490.KS", "005490.KS"))
        assertEquals("005830", resolvedKrCompanyName("005830.KS", "005830"))
        assertEquals("기존이름", resolvedKrCompanyName("005930.KS", "기존이름"))
        assertEquals("AAPL", resolvedKrCompanyName("AAPL", "AAPL"))
    }

    @Test
    fun englishNamesStayEnglishWhenNoKoreanOverrideExists() {
        assertEquals("BOTZ", displayCompanyName("BOTZ", "BOTZ"))
        assertEquals("Global X Robotics and AI", displayCompanyName("Global X Robotics and AI", "BOTZ"))
        assertEquals("BOTZ", displayCompanyName("BOTZ 기업", "BOTZ"))
        assertEquals("BOTZ", localizedCompanyName("BOTZ", "BOTZ 기업", "US"))
    }

    @Test
    fun numericFormattersHandleInvalidValues() {
        assertEquals("-", pct(Double.NaN))
        assertEquals("-", num(Double.POSITIVE_INFINITY))
        assertEquals("-", fmtPx(Double.NEGATIVE_INFINITY))
        assertEquals("-", cap(null, "KRW"))
    }

    @Test
    fun priceAndCurrencyFormattingMatchesMarkets() {
        assertEquals("KRW", marketCurrency("005930.KS", null))
        assertEquals("KRW", marketCurrency("005930", "KR"))
        assertEquals("USD", marketCurrency("AAPL", null))
        assertEquals("₩72,500", fmtPx(72_500.0, "KRW"))
        assertEquals("$123.45", fmtPx(123.45, "USD"))
        assertEquals("$1234.56", fmtPx(1_234.56, "USD"))
        assertEquals("$0.88", fmtPx(0.8800, "USD"))
        assertEquals("$0.885", fmtPx(0.8850, "USD"))
        assertEquals("$0.80", fmtPx(0.8000, "USD"))
        assertEquals("+$0.88", signedPx(0.8800, "USD"))
        assertEquals("-$0.885", signedPx(-0.8850, "USD"))
    }

    @Test
    fun searchMatchesTickerNameAndSector() {
        assertTrue(matches("sam", "005930.KS", "Samsung Electronics", "Technology"))
        assertTrue(matches("반도체", "005930.KS", "삼성전자", "반도체"))
        assertFalse(matches("bank", "005930.KS", "삼성전자", "반도체"))
    }

    @Test
    fun updateTimestampFormattingNormalizesApiValues() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            assertEquals("2026-05-13 12:38", formattedUpdateTimestamp("2026-05-13T03:38:57+00:00"))
            assertEquals("2026-05-13 12:38", formattedUpdateTimestamp("2026-05-13T03:38:57.123456"))
            assertEquals("2026-05-13 03:38", formattedUpdateTimestamp("generated 2026-05-13 03:38:57"))
            assertEquals("-", formattedUpdateTimestamp(null))
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }
}
