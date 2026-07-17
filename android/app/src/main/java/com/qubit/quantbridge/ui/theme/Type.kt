package com.qubit.quantbridge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BaseFamily = FontFamily.Default
private const val TabularNumberFeature = "tnum"

val Typography = Typography(
    headlineMedium = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    headlineSmall = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    titleLarge = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    titleMedium = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    titleSmall = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    bodyLarge = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    bodyMedium = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    bodySmall = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    labelLarge = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    labelMedium = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    ),
    labelSmall = TextStyle(
        fontFamily = BaseFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumberFeature
    )
)
