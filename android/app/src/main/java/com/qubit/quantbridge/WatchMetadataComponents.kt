package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantWarning

internal val watchTagOptions = listOf("실적", "저평가", "모멘텀", "리스크", "공부", "매수후보")
internal val watchJudgmentAlertOptions = listOf("실적 리스크", "가설 흔들림", "점수·과열 동시", "성향 관찰", "판단 업데이트")
internal val watchAlertOptions = watchJudgmentAlertOptions
internal val watchHorizonOptions = listOf("1개월", "3개월", "6개월", "1년+")
internal val watchReviewStatusOptions = listOf("유지", "수정", "종료")

internal fun watchAlertOptionMatches(selectedOptions: List<String>, option: String): Boolean {
    if (selectedOptions.isEmpty()) return true
    val aliases = watchAlertAliases[option].orEmpty() + option
    return selectedOptions.any { it in aliases }
}

internal fun watchAlertDisplayLabel(option: String): String {
    return watchJudgmentAlertOptions.firstOrNull { label ->
        option in watchAlertAliases[label].orEmpty() || option == label
    } ?: option
}

internal val watchAlertAliases = mapOf(
    "실적 리스크" to listOf("실적 리스크", "실적 D-3", "실적 D-1"),
    "가설 흔들림" to listOf("가설 흔들림", "투자 가설 흔들림", "우선순위 상승"),
    "점수·과열 동시" to listOf("점수·과열 동시", "가격 급변", "우선순위 상승"),
    "성향 관찰" to listOf("성향 관찰"),
    "판단 업데이트" to listOf("판단 업데이트", "데이터 갱신", "우선순위 상승")
)
