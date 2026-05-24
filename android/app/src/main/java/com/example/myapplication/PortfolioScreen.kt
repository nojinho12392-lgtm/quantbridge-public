package com.example.myapplication

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun PortfolioScreen(
    app: QuantAppState,
    portfolioViewModel: PortfolioViewModel = hiltViewModel(),
    smallCapViewModel: SmallCapViewModel = hiltViewModel(),
    comparisonViewModel: ComparisonViewModel = hiltViewModel()
) {
    PortfolioScreenContent(app, portfolioViewModel, smallCapViewModel, comparisonViewModel)
}
