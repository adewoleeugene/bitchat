package com.bitchat.android.di

import android.content.Context
import androidx.room.Room
import com.bitchat.android.data.local.FeedDao
import com.bitchat.android.data.local.NotarizationDao
import com.bitchat.android.data.local.SolanaDatabase
import com.bitchat.android.data.local.TokenGateDao
import com.bitchat.android.data.local.TransactionDao
import com.bitchat.android.data.local.WalletDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideSolanaDatabase(@ApplicationContext context: Context): SolanaDatabase {
        return Room.databaseBuilder(
            context,
            SolanaDatabase::class.java,
            "solana_database"
        ).addMigrations(
            SolanaDatabase.MIGRATION_1_2,
            SolanaDatabase.MIGRATION_2_3,
            SolanaDatabase.MIGRATION_3_4,
            SolanaDatabase.MIGRATION_4_5,
            SolanaDatabase.MIGRATION_5_6,
            SolanaDatabase.MIGRATION_6_7,
            SolanaDatabase.MIGRATION_7_8,
            SolanaDatabase.MIGRATION_8_9,
            SolanaDatabase.MIGRATION_9_10
        )
        .build()
    }

    @Provides
    fun provideWalletDao(database: SolanaDatabase): WalletDao {
        return database.walletDao()
    }

    @Provides
    fun provideTransactionDao(database: SolanaDatabase): TransactionDao {
        return database.transactionDao()
    }

    @Provides
    fun provideTokenGateDao(database: SolanaDatabase): TokenGateDao {
        return database.tokenGateDao()
    }

    @Provides
    fun provideNotarizationDao(database: SolanaDatabase): NotarizationDao {
        return database.notarizationDao()
    }

    @Provides
    fun provideFeedDao(database: SolanaDatabase): FeedDao {
        return database.feedDao()
    }
}
