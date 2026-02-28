package com.bitchat.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bitchat.android.data.local.entities.TokenGateConfigEntity
import com.bitchat.android.data.local.entities.TokenGateEligibilityCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TokenGateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokenGate(config: TokenGateConfigEntity)

    @Query("SELECT * FROM token_gate_configs WHERE channelKey = :channelKey")
    suspend fun getTokenGate(channelKey: String): TokenGateConfigEntity?

    @Query("SELECT * FROM token_gate_configs WHERE channelKey = :channelKey")
    fun observeTokenGate(channelKey: String): Flow<TokenGateConfigEntity?>

    @Query("SELECT * FROM token_gate_configs ORDER BY createdAt DESC")
    fun observeAllTokenGates(): Flow<List<TokenGateConfigEntity>>

    @Query("SELECT * FROM token_gate_configs ORDER BY createdAt DESC")
    suspend fun getAllTokenGates(): List<TokenGateConfigEntity>

    @Query("UPDATE token_gate_configs SET isUserEligible = :eligible, lastValidatedAt = :validatedAt WHERE channelKey = :channelKey")
    suspend fun updateEligibility(channelKey: String, eligible: Boolean, validatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM token_gate_configs WHERE channelKey = :channelKey")
    suspend fun deleteTokenGate(channelKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM token_gate_configs WHERE channelKey = :channelKey)")
    suspend fun isTokenGated(channelKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEligibilityCache(entry: TokenGateEligibilityCacheEntity)

    @Query("""
        SELECT * FROM token_gate_eligibility_cache
        WHERE channelKey = :channelKey
          AND walletAddress = :walletAddress
          AND gateHash = :gateHash
        LIMIT 1
    """)
    suspend fun getEligibilityCache(
        channelKey: String,
        walletAddress: String,
        gateHash: String
    ): TokenGateEligibilityCacheEntity?

    @Query("DELETE FROM token_gate_eligibility_cache WHERE channelKey = :channelKey")
    suspend fun deleteEligibilityCacheForChannel(channelKey: String)
}
