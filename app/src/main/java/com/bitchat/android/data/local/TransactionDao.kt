package com.bitchat.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: QueuedTransactionEntity)

    @Query("SELECT * FROM queued_transactions WHERE id = :id")
    suspend fun getTransaction(id: String): QueuedTransactionEntity?

    @Update
    suspend fun updateTransaction(tx: QueuedTransactionEntity)

    @Query("SELECT * FROM queued_transactions WHERE status IN ('QUEUED', 'PENDING') AND ttlExpiresAt > :now ORDER BY createdAt ASC")
    suspend fun getPendingTransactions(now: Long = System.currentTimeMillis()): List<QueuedTransactionEntity>

    @Query("SELECT * FROM queued_transactions WHERE status = 'BROADCASTING' AND txSignature IS NOT NULL ORDER BY lastAttemptAt ASC")
    suspend fun getBroadcastingWithSignature(): List<QueuedTransactionEntity>

    @Query("UPDATE queued_transactions SET status = 'QUEUED' WHERE status = 'BROADCASTING' AND lastAttemptAt < :staleThreshold AND ttlExpiresAt > :now")
    suspend fun recoverStaleBroadcasting(staleThreshold: Long = System.currentTimeMillis() - 120_000L, now: Long = System.currentTimeMillis())

    @Query("UPDATE queued_transactions SET status = 'QUEUED' WHERE status = 'AWAITING_BLOCKHASH' AND lastAttemptAt < :staleThreshold AND ttlExpiresAt > :now")
    suspend fun recoverStaleHandshake(staleThreshold: Long = System.currentTimeMillis() - 30_000L, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM queued_transactions WHERE status IN ('QUEUED', 'PENDING') AND ttlExpiresAt > :now ORDER BY createdAt ASC")
    fun observePendingTransactions(now: Long = System.currentTimeMillis()): Flow<List<QueuedTransactionEntity>>

    @Query("SELECT * FROM queued_transactions WHERE senderPublicKey = :publicKey OR recipientPublicKey = :publicKey ORDER BY createdAt DESC")
    fun observeTransactionsForWallet(publicKey: String): Flow<List<QueuedTransactionEntity>>

    @Query("SELECT * FROM queued_transactions ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentTransactions(limit: Int = 50): Flow<List<QueuedTransactionEntity>>

    @Query("UPDATE queued_transactions SET status = :status, txSignature = :signature, lastAttemptAt = :attemptAt, attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, signature: String? = null, attemptAt: Long = System.currentTimeMillis())

    @Query("UPDATE queued_transactions SET status = 'BROADCASTING', txSignature = :signature, errorMessage = NULL, lastAttemptAt = :attemptAt WHERE id = :id")
    suspend fun markBroadcastObserved(id: String, signature: String, attemptAt: Long = System.currentTimeMillis())

    @Query("UPDATE queued_transactions SET status = 'CONFIRMED', txSignature = :signature, errorMessage = NULL, lastAttemptAt = :attemptAt WHERE id = :id")
    suspend fun markConfirmed(id: String, signature: String, attemptAt: Long = System.currentTimeMillis())

    @Query("UPDATE queued_transactions SET status = 'FAILED', errorMessage = :error, lastAttemptAt = :attemptAt, attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun markFailed(id: String, error: String, attemptAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM queued_transactions WHERE status = 'CONFIRMED' AND createdAt < :olderThan")
    suspend fun pruneConfirmed(olderThan: Long)

    @Query("UPDATE queued_transactions SET status = 'FAILED', errorMessage = 'TTL expired' WHERE status IN ('QUEUED', 'PENDING') AND ttlExpiresAt < :now")
    suspend fun expireStaleTransactions(now: Long = System.currentTimeMillis())
}
