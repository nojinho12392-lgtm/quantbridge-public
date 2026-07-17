package com.qubit.quantbridge.di

import android.content.Context
import com.qubit.quantbridge.ApiResponseCache
import com.qubit.quantbridge.SecureTokenStore
import com.qubit.quantbridge.network.AuthTokenProvider
import com.qubit.quantbridge.network.HttpClientFactory
import com.qubit.quantbridge.network.QuantApiService
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): SecureTokenStore {
        return SecureTokenStore(context)
    }

    @Provides
    @Singleton
    fun provideAuthTokenProvider(tokenStore: SecureTokenStore): AuthTokenProvider {
        return AuthTokenProvider { tokenStore.loadToken() }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        tokenProvider: AuthTokenProvider,
        responseCache: ApiResponseCache
    ): OkHttpClient {
        return HttpClientFactory.createOkHttpClient(
            context = context,
            tokenProvider = tokenProvider,
            responseCache = responseCache
        )
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(apiBaseUrl())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideQuantApiService(retrofit: Retrofit): QuantApiService {
        return retrofit.create(QuantApiService::class.java)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun apiBaseUrl(): String {
        return HttpClientFactory.primaryBaseUrl()
    }
}
