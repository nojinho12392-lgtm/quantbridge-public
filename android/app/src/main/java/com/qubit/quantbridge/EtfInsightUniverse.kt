package com.qubit.quantbridge

val etfInsightUniverse = etfInsightUniversePartA + etfInsightUniversePartB

val featuredEtfInsights: List<EtfInsight>
    get() = etfInsightUniverse.take(4)
