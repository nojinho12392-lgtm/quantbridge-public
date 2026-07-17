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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun NewsScreen(
    app: QuantAppState,
    contentTopPadding: Dp = 10.dp,
    contentBottomPadding: Dp = FLOATING_NAV_CONTENT_INSET,
    showControls: Boolean = true,
    showSummary: Boolean = true,
    useImpactFeed: Boolean = true,
    newsViewModel: NewsViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("ALL") }
    var selectedArticle by remember { mutableStateOf<NewsItem?>(null) }

    LaunchedEffect(Unit) {
        newsViewModel.ensureNewsLoaded("ALL")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentTopPadding,
            end = 16.dp,
            bottom = contentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (showControls) {
            item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftSegmentSwitch(
                        options = listOf("ALL", "US", "KR"),
                        selected = market,
                        onSelect = {
                            market = it
                            newsViewModel.refreshNews(query, market)
                        }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BorderlessSearchField(
                            query = query,
                            onQuery = { query = it },
                            placeholder = "종목명, 지수, 키워드 검색",
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { newsViewModel.refreshNews(query, market) }) {
                            LucideIconView(
                                icon = LucideIcon.Search,
                                contentDescription = "뉴스 검색",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    newsViewModel.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (showSummary) {
            item {
                HeaderCard(
                    title = "시장 영향 뉴스",
                    value = "${newsViewModel.items.size}개",
                    subtitle = "원문은 링크로 열고, 큐빗 분석과 관련 가격 반응만 표시합니다.",
                    trailing = if (newsViewModel.loading) "동기화" else market,
                    quiet = true
                )
            }
        }
        if (newsViewModel.items.isEmpty()) {
            item {
                if (newsViewModel.loading) {
                    SkeletonLoadingCard(lineCount = 2)
                } else {
                    EmptyCard(
                        "뉴스 없음",
                        "표시할 외부 기사가 없습니다. 시장 선택이나 검색어를 바꿔보세요.",
                        lucideIcon = LucideIcon.Newspaper,
                        actionLabel = "다시 불러오기",
                        onAction = { newsViewModel.refreshNews(query, market) }
                    )
                }
            }
        } else {
            itemsIndexed(newsViewModel.items, key = { _, item -> item.id }) { index, item ->
                if (useImpactFeed) {
                    NewsImpactFeedCard(
                        item = item,
                        featured = index == 0,
                        onOpen = {
                            if (item.url.isNotBlank()) {
                                selectedArticle = item
                            }
                        }
                    )
                } else {
                    NewsCard(
                        item = item,
                        onOpen = {
                            if (item.url.isNotBlank()) {
                                selectedArticle = item
                            }
                        }
                    )
                }
            }
        }
    }
    selectedArticle?.let { item ->
        InAppNewsBrowserDialog(
            item = item,
            onDismiss = { selectedArticle = null }
        )
    }
}
