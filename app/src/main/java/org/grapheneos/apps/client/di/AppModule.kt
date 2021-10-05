package org.grapheneos.apps.client.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper
import org.grapheneos.apps.client.utils.network.MetaDataHelper
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun providerOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(30))
            .connectTimeout(Duration.ofSeconds(30))
            .pingInterval(Duration.ofSeconds(10))
            .build()

    @Singleton
    @Provides
    fun provideMetaDataHelper(
        okHttpClient: OkHttpClient,
        @ApplicationContext context: Context
    ): MetaDataHelper = MetaDataHelper(okHttpClient, context)

    @Singleton
    @Provides
    fun provideApkDownloadHelper(
        okHttpClient: OkHttpClient,
        @ApplicationContext context: Context
    ): ApkDownloadHelper = ApkDownloadHelper(okHttpClient, context)

}