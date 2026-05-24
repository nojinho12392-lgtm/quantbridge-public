package com.example.myapplication.di

import android.content.Context
import com.example.myapplication.ApiResponseCache
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
