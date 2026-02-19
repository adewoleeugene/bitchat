package com.bitchat.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bitchat.android.data.local.entities.WalletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: WalletEntity)

    @Update
    suspend fun updateWallet(wallet: WalletEntity)

    @Query("SELECT * FROM wallets WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveWallet(): WalletEntity?

    @Query("SELECT * FROM wallets WHERE isActive = 1 LIMIT 1")
    fun observeActiveWallet(): Flow<WalletEntity?>

    @Query("SELECT * FROM wallets ORDER BY createdAt DESC")
    fun observeAllWallets(): Flow<List<WalletEntity>>

    @Query("UPDATE wallets SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE wallets SET lastBalanceLamports = :lamports, lastBalanceUpdatedAt = :updatedAt WHERE publicKey = :publicKey")
    suspend fun updateBalance(publicKey: String, lamports: Long, updatedAt: Long)

    @Query("DELETE FROM wallets WHERE publicKey = :publicKey")
    suspend fun deleteWallet(publicKey: String)
}
