package com.bitchat.android.data.local

import androidx.room.*
import com.bitchat.android.data.local.entities.MessageNotarizationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotarizationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageNotarizationEntity)

    @Query("SELECT * FROM message_notarizations WHERE messageId = :messageId")
    suspend fun getByMessageId(messageId: String): MessageNotarizationEntity?

    @Query("SELECT * FROM message_notarizations WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: String): List<MessageNotarizationEntity>

    @Query("SELECT * FROM message_notarizations WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getQueuedBatch(limit: Int = 10): List<MessageNotarizationEntity>

    @Query("UPDATE message_notarizations SET status = :status, batchId = :batchId WHERE messageId IN (:messageIds)")
    suspend fun updateStatusBatch(messageIds: List<String>, status: String, batchId: String?)

    @Query("UPDATE message_notarizations SET status = :status, txSignature = :txSignature, slot = :slot, blockTime = :blockTime, batchId = :batchId WHERE messageId IN (:messageIds)")
    suspend fun updateConfirmed(messageIds: List<String>, status: String, txSignature: String, slot: Long?, blockTime: Long?, batchId: String?)

    @Query("UPDATE message_notarizations SET status = :status, errorMessage = :errorMessage WHERE messageId IN (:messageIds)")
    suspend fun updateFailed(messageIds: List<String>, status: String, errorMessage: String?)

    @Query("SELECT * FROM message_notarizations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MessageNotarizationEntity>>

    @Query("SELECT COUNT(*) FROM message_notarizations WHERE status = 'QUEUED'")
    fun observeQueuedCount(): Flow<Int>

    @Query("DELETE FROM message_notarizations WHERE status = 'CONFIRMED' AND createdAt < :beforeTimestamp")
    suspend fun pruneOldConfirmed(beforeTimestamp: Long)
}
