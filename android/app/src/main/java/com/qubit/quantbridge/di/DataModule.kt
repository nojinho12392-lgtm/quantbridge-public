package com.qubit.quantbridge.di

import android.content.Context
import com.qubit.quantbridge.ApiResponseCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideApiResponseCache(@ApplicationContext context: Context): ApiResponseCache {
        return ApiResponseCache(context.cacheDir)
    }
}
