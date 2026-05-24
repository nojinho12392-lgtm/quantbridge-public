package com.example.myapplication

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun PulseScreen(
    app: QuantAppState,
    pulseViewModel: PulseViewModel = hiltViewModel(),
    newsViewModel: NewsViewModel = hiltViewModel()
) {
    InsightScreenContent(app, pulseViewModel, newsViewModel)
}
