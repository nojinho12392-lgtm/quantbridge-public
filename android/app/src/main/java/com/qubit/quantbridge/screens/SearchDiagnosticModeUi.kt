package com.qubit.quantbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListScope
import kotlinx.coroutines.CoroutineScope

internal fun LazyListScope.searchDiagnosticModeItems(
    opsViewModel: OpsViewModel,
    onDiagnosticInfo: (DiagnosticInfo) -> Unit
) {
    val quality = opsViewModel.researchQuality
    val mlBlend = opsViewModel.mlBlendReport
    val policyRankings = opsViewModel.policyAdjustedRankings
    val ops = opsViewModel.opsHealth
    item {
        DiagnosticHeaderCard(
            title = "리서치 품질",
            value = quality?.overallStatus ?: "-",
            subtitle = "경고 ${quality?.warningCount ?: 0} · 운영 가능 ${quality?.productionReadyCount ?: 0} · Proxy ${quality?.proxyEvidenceCount ?: 0}",
            trailing = "${quality?.items?.size ?: 0}",
            onClick = { onDiagnosticInfo(researchQualityDiagnosticInfo(quality) )}
        )
    }
    item {
        DiagnosticHeaderCard(
            title = "AI 보정",
            value = mlBlend?.status ?: "-",
            subtitle = "AI ${pct(mlBlend?.latest?.mlWeight, signed = false)} · 기본 점수 ${pct(mlBlend?.latest?.factorWeight, signed = false)} · 예측력 ${num(mlBlend?.latest?.rankIc)}",
            trailing = mlBlend?.latest?.predictedStocks?.toInt()?.toString() ?: "${mlBlend?.items?.size ?: 0}",
            onClick = { onDiagnosticInfo(mlBlendDiagnosticInfo(mlBlend) )}
        )
    }
    item {
        DiagnosticHeaderCard(
            title = "정책 섀도 랭킹",
            value = policyRankingHeaderValue(policyRankings),
            subtitle = policyRankingHeaderSubtitle(policyRankings),
            trailing = policyRankings.sumOf { it.items.size }.takeIf { it > 0 }?.toString() ?: "-",
            onClick = { onDiagnosticInfo(policyAdjustedRankingDiagnosticInfo(policyRankings) )}
        )
    }
    item {
        DiagnosticHeaderCard(
            title = "운영 상태",
            value = ops?.status ?: "-",
            subtitle = "체크 ${ops?.checks?.size ?: 0} · 생성 ${ops?.generatedAt?.take(16) ?: "-"}",
            trailing = if (ops?.healthy == true) "OK" else "확인",
            onClick = { onDiagnosticInfo(opsHealthDiagnosticInfo(ops) )}
        )
    }
    item { SectionTitle("정책 조정 섀도 랭킹", "${policyRankings.size}") }
    if (policyRankings.isEmpty()) {
        item { EmptyCard("정책 섀도 랭킹 없음", "정책 조정 랭킹 데이터가 아직 없습니다.") }
    } else {
        items(policyRankings, key = { it.market }) { ranking ->
            PolicyAdjustedRankingBlock(ranking)
        }
    }
    item { SectionTitle("AI 보정 리포트", "${mlBlend?.items?.size ?: 0}") }
    if (mlBlend?.items.isNullOrEmpty()) {
        item { EmptyCard("AI 보정 리포트 없음", "AI 보정 데이터가 아직 없습니다.") }
    } else {
        itemsIndexed(
            mlBlend!!.items.take(20),
            key = { index, item -> "${item.market}:${item.generated}:$index" }
        ) { _, item ->
            StatusRow(
                title = "${item.market} ${item.model}",
                status = item.status ?: mlBlend!!.status,
                subtitle = "AI ${pct(item.mlWeight, signed = false)} · 기본 점수 ${pct(item.factorWeight, signed = false)} · 예측력 ${num(item.rankIc)} · 독립성 ${num(item.mlFactorSpearman)}"
            )
        }
    }
    item { SectionTitle("Signal Quality Gates", "${quality?.items?.size ?: 0}") }
    if (quality?.items.isNullOrEmpty()) {
        item { EmptyCard("품질 게이트 없음", "research-quality 실행 결과가 아직 없습니다.") }
    } else {
        itemsIndexed(
            quality!!.items.take(30),
            key = { index, gate -> "${gate.market}:${gate.factor}:$index" }
        ) { _, gate ->
            StatusRow(
                title = "${gate.market} ${gate.factor}",
                status = gate.status,
                subtitle = "IC ${num(gate.meanIc)} · 양수율 ${pct(gate.positiveRate, signed = false)} · 스냅샷 ${gate.snapshots?.toInt() ?: 0}"
            )
        }
    }
    item { SectionTitle("Ops Checks", "${ops?.checks?.size ?: 0}") }
    if (ops?.checks.isNullOrEmpty()) {
        item { EmptyCard("운영 체크 없음", "API 운영 상태를 불러오지 못했습니다.") }
    } else {
        items(ops!!.checks, key = { it.name }) { check ->
            StatusRow(check.name, check.status, check.message.ifBlank { "세부 메시지 없음" })
        }
    }
}
