package com.qubit.quantbridge

import com.qubit.quantbridge.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlindFinancialQuizRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchQuiz(refresh: Boolean = false): BlindFinancialQuizResponse {
        return api.getBlindFinancialQuiz(market = "US", refresh = refresh)
    }
}
