package com.bitchat.android.solana

import com.bitchat.android.data.local.NotarizationDao
import com.bitchat.android.data.local.entities.MessageNotarizationEntity
import com.bitchat.android.data.local.entities.NotarizationStatus
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class MessageNotarizationServiceTest {

    @Test
    fun queueNotarization_insertsQueuedRecord() = runBlocking {
        val dao = FakeNotarizationDao()
        val service = createService(
            dao = dao,
            hasInternet = false
        )

        val message = BitchatMessage(
            id = "m1",
            sender = "alice",
            content = "hello",
            timestamp = Date(1_735_000_000_000L)
        )

        val result = service.queueNotarization(message)
        val stored = dao.getByMessageId("m1")

        assertTrue(result.isSuccess)
        assertNotNull(stored)
        assertEquals(NotarizationStatus.QUEUED, stored?.status)

        service.shutdown()
    }

    @Test
    fun processBatch_offlineLeavesItemsQueued() = runBlocking {
        val dao = FakeNotarizationDao()
        dao.insert(
            MessageNotarizationEntity(
                messageId = "m1",
                messageHash = "abc",
                senderNickname = "alice",
                contentPreview = "hello",
                messageTimestamp = 1_735_000_000_000L,
                status = NotarizationStatus.QUEUED,
                createdAt = 1_735_000_000_001L
            )
        )

        val service = createService(
            dao = dao,
            hasInternet = false
        )

        val processed = service.processBatch()
        val stored = dao.getByMessageId("m1")

        assertTrue(processed.isSuccess)
        assertEquals(0, processed.getOrNull())
        assertEquals(NotarizationStatus.QUEUED, stored?.status)

        service.shutdown()
    }

    @Test
    fun processBatch_retryableSendErrorRequeues() = runBlocking {
        val dao = FakeNotarizationDao()
        dao.insert(
            MessageNotarizationEntity(
                messageId = "m1",
                messageHash = "abc",
                senderNickname = "alice",
                contentPreview = "hello",
                messageTimestamp = 1_735_000_000_000L,
                status = NotarizationStatus.QUEUED,
                createdAt = 1_735_000_000_001L
            )
        )

        val service = createService(
            dao = dao,
            hasInternet = true,
            sendResult = Result.failure(IllegalStateException("timeout while posting"))
        )

        val processed = service.processBatch()
        val stored = dao.getByMessageId("m1")

        assertTrue(processed.isFailure)
        assertEquals(NotarizationStatus.QUEUED, stored?.status)
        assertTrue((stored?.errorMessage ?: "").contains("timeout"))

        service.shutdown()
    }

    @Test
    fun startupRecovery_resetsInterruptedBroadcastingToQueued() = runBlocking {
        val dao = FakeNotarizationDao()
        dao.insert(
            MessageNotarizationEntity(
                messageId = "m1",
                messageHash = "abc",
                senderNickname = "alice",
                contentPreview = "hello",
                messageTimestamp = 1_735_000_000_000L,
                status = NotarizationStatus.BROADCASTING,
                createdAt = 1_735_000_000_001L,
                txSignature = null,
                batchId = "old-batch"
            )
        )

        val service = createService(
            dao = dao,
            hasInternet = false
        )

        // Startup recovery runs asynchronously in init.
        kotlinx.coroutines.delay(100)

        val stored = dao.getByMessageId("m1")
        assertEquals(NotarizationStatus.QUEUED, stored?.status)

        service.shutdown()
    }

    private fun createService(
        dao: FakeNotarizationDao,
        hasInternet: Boolean,
        sendResult: Result<String> = Result.success("5ig123sig")
    ): MessageNotarizationService {
        val wallet = object : NotarizationWalletGateway {
            override fun getPublicKeyBase58(): String = "11111111111111111111111111111111"
            override fun sign(data: ByteArray): ByteArray = ByteArray(64) { 1 }
        }
        val rpc = object : NotarizationRpcGateway {
            override suspend fun getLatestBlockhash(): Result<BlockhashInfo> {
                return Result.success(BlockhashInfo("11111111111111111111111111111111", 123L))
            }

            override suspend fun sendTransaction(signedTransactionBase64: String): Result<String> {
                return sendResult
            }

            override suspend fun confirmTransaction(signature: String): Result<Boolean> {
                return Result.success(false)
            }

            override suspend fun getTransactionInfo(signature: String): Result<TransactionInfo> {
                return Result.success(TransactionInfo(slot = 999L, blockTime = 1_735_000_100L))
            }
        }
        return MessageNotarizationService(
            walletGateway = wallet,
            rpcGateway = rpc,
            notarizationDao = dao,
            hasInternetConnectivity = { hasInternet }
        )
    }
}

private class FakeNotarizationDao : NotarizationDao {
    private val data = linkedMapOf<String, MessageNotarizationEntity>()
    private val flow = MutableStateFlow<List<MessageNotarizationEntity>>(emptyList())

    override suspend fun insert(entity: MessageNotarizationEntity) {
        data[entity.messageId] = entity
        emit()
    }

    override suspend fun getByMessageId(messageId: String): MessageNotarizationEntity? {
        return data[messageId]
    }

    override suspend fun getByStatus(status: String): List<MessageNotarizationEntity> {
        return data.values.filter { it.status == status }.sortedBy { it.createdAt }
    }

    override suspend fun getQueuedBatch(limit: Int): List<MessageNotarizationEntity> {
        return data.values.filter { it.status == NotarizationStatus.QUEUED }.sortedBy { it.createdAt }.take(limit)
    }

    override suspend fun updateStatusBatch(messageIds: List<String>, status: String, batchId: String?) {
        messageIds.forEach { id ->
            val existing = data[id] ?: return@forEach
            data[id] = existing.copy(status = status, batchId = batchId)
        }
        emit()
    }

    override suspend fun updateBroadcasted(messageIds: List<String>, status: String, txSignature: String, batchId: String?) {
        messageIds.forEach { id ->
            val existing = data[id] ?: return@forEach
            data[id] = existing.copy(status = status, txSignature = txSignature, batchId = batchId)
        }
        emit()
    }

    override suspend fun updateConfirmed(
        messageIds: List<String>,
        status: String,
        txSignature: String,
        slot: Long?,
        blockTime: Long?,
        batchId: String?
    ) {
        messageIds.forEach { id ->
            val existing = data[id] ?: return@forEach
            data[id] = existing.copy(
                status = status,
                txSignature = txSignature,
                slot = slot,
                blockTime = blockTime,
                batchId = batchId
            )
        }
        emit()
    }

    override suspend fun updateFailed(messageIds: List<String>, status: String, errorMessage: String?) {
        messageIds.forEach { id ->
            val existing = data[id] ?: return@forEach
            data[id] = existing.copy(status = status, errorMessage = errorMessage)
        }
        emit()
    }

    override fun observeAll(): Flow<List<MessageNotarizationEntity>> = flow

    override fun observeQueuedCount(): Flow<Int> {
        return flow.map { list -> list.count { it.status == NotarizationStatus.QUEUED } }
    }

    override suspend fun recoverInterruptedBroadcasts(): Int {
        var changed = 0
        data.keys.toList().forEach { id ->
            val existing = data[id] ?: return@forEach
            if (existing.status == NotarizationStatus.BROADCASTING && existing.txSignature == null) {
                data[id] = existing.copy(status = NotarizationStatus.QUEUED, batchId = null)
                changed += 1
            }
        }
        if (changed > 0) emit()
        return changed
    }

    override suspend fun getBroadcastingWithSignature(): List<MessageNotarizationEntity> {
        return data.values.filter {
            it.status == NotarizationStatus.BROADCASTING && !it.txSignature.isNullOrBlank()
        }.sortedBy { it.createdAt }
    }

    override suspend fun pruneOldConfirmed(beforeTimestamp: Long) {
        val toRemove = data.values
            .filter { it.status == NotarizationStatus.CONFIRMED && it.createdAt < beforeTimestamp }
            .map { it.messageId }
        toRemove.forEach { data.remove(it) }
        emit()
    }

    private fun emit() {
        flow.value = data.values.sortedByDescending { it.createdAt }
    }
}
