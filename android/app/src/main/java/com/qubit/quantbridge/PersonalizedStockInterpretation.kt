package com.qubit.quantbridge

import java.util.Locale
import kotlin.math.abs

data class PersonalizedStockInterpretation(
    val label: String,
    val headline: String,
    val detail: String,
    val action: String,
    val reasons: List<String>,
    val tone: DetailTone
) {
    val decisionLine: String
        get() = "$headline. $action"
}
