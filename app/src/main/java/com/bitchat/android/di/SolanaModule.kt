package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.solana.SolanaRpcService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SolanaModule {

    @Provides
    @Singleton
    @Named("solanaRpcUrl")
    fun provideSolanaRpcUrl(): String {
        // Default to devnet; switch to mainnet-beta for production
        return "https://api.devnet.solana.com"
    }

    @Provides
    @Singleton
    @Named("solanaOkHttp")
    fun provideSolanaOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideSolanaRpcService(
        @Named("solanaOkHttp") okHttpClient: OkHttpClient,
        @Named("solanaRpcUrl") rpcUrl: String
    ): SolanaRpcService {
        return SolanaRpcService(okHttpClient, rpcUrl)
    }
}
