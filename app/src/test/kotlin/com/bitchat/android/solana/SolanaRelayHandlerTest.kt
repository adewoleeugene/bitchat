package com.bitchat.android.solana

import com.bitchat.android.data.local.TransactionDao
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.models.TransactionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaRelayHandlerTest {

    @Test
    fun handleRelayReceipt_broadcastMarksBroadcasting() = runBlocking {
        val dao = FakeTransactionDao()
        val requestId = "tx-broadcast-1"
        dao.insertTransaction(baseTx(requestId))

        val handler = createHandler(dao)
        handler.trackOutgoingRequest(requestId)

        val accepted = handler.handleRelayReceipt(
            SolanaRelayReceipt(
                requestId = requestId,
                status = RelayReceiptStatus.BROADCAST,
                txSignature = "sig123",
                errorMessage = ""
            )
        )

        assertTrue(accepted)
        kotlinx.coroutines.delay(50)
        val updated = dao.getTransaction(requestId)!!
        assertEquals(TransactionStatus.BROADCASTING.value, updated.status)
        assertEquals("sig123", updated.txSignature)

        handler.shutdown()
    }

    @Test
    fun handleRelayReceipt_confirmedMarksConfirmed() = runBlocking {
        val dao = FakeTransactionDao()
        val requestId = "tx-confirmed-1"
        dao.insertTransaction(baseTx(requestId))

        val handler = createHandler(dao)
        handler.trackOutgoingRequest(requestId)

        val accepted = handler.handleRelayReceipt(
            SolanaRelayReceipt(
                requestId = requestId,
                status = RelayReceiptStatus.CONFIRMED,
                txSignature = "sig999",
                errorMessage = ""
            )
        )

        assertTrue(accepted)
        kotlinx.coroutines.delay(50)
        val updated = dao.getTransaction(requestId)!!
        assertEquals(TransactionStatus.CONFIRMED.value, updated.status)
        assertEquals("sig999", updated.txSignature)

        handler.shutdown()
    }

    @Test
    fun handleRelayReceipt_retryableFailureRequeues() = runBlocking {
        val dao = FakeTransactionDao()
        val requestId = "tx-retry-1"
        dao.insertTransaction(baseTx(requestId))

        val handler = createHandler(dao)
        handler.trackOutgoingRequest(requestId)

        val accepted = handler.handleRelayReceipt(
            SolanaRelayReceipt(
                requestId = requestId,
                status = RelayReceiptStatus.FAILED,
                txSignature = "",
                errorMessage = "Relay peer has no internet"
            )
        )

        assertTrue(accepted)
        kotlinx.coroutines.delay(50)
        val updated = dao.getTransaction(requestId)!!
        assertEquals(TransactionStatus.QUEUED.value, updated.status)
        assertTrue(handler.hasPendingRequest(requestId))

        handler.shutdown()
    }

    @Test
    fun handleRelayReceipt_nonRetryableFailureMarksFailed() = runBlocking {
        val dao = FakeTransactionDao()
        val requestId = "tx-failed-1"
        dao.insertTransaction(baseTx(requestId))

        val handler = createHandler(dao)
        handler.trackOutgoingRequest(requestId)

        val accepted = handler.handleRelayReceipt(
            SolanaRelayReceipt(
                requestId = requestId,
                status = RelayReceiptStatus.FAILED,
                txSignature = "",
                errorMessage = "signature verification failed"
            )
        )

        assertTrue(accepted)
        kotlinx.coroutines.delay(50)
        val updated = dao.getTransaction(requestId)!!
        assertEquals(TransactionStatus.FAILED.value, updated.status)
        assertEquals("signature verification failed", updated.errorMessage)
        assertTrue(!handler.hasPendingRequest(requestId))

        handler.shutdown()
    }

    private fun createHandler(dao: FakeTransactionDao): SolanaRelayHandler {
        val rpc = object : RelayRpcGateway {
            override suspend fun sendTransaction(signedTxBase64: String): Result<String> =
                Result.success("sig")

            override suspend fun getLatestBlockhash(): Result<BlockhashInfo> =
                Result.success(BlockhashInfo("11111111111111111111111111111111", 1L))

            override suspend fun confirmTransaction(signature: String): Result<Boolean> =
                Result.success(true)
        }
        return SolanaRelayHandler(rpcGateway = rpc, transactionDao = dao)
    }

    private fun baseTx(id: String): QueuedTransactionEntity =
        QueuedTransactionEntity(
            id = id,
            signedTransactionBase64 = "",
            senderPublicKey = "sender11111111111111111111111111111111",
            recipientPublicKey = "recipient1111111111111111111111111111",
            amountLamports = 1_000_000L,
            status = TransactionStatus.QUEUED.value,
            createdAt = System.currentTimeMillis(),
            ttlExpiresAt = System.currentTimeMillis() + 86_400_000L
        )
}

private class FakeTransactionDao : TransactionDao {
    private val data = linkedMapOf<String, QueuedTransactionEntity>()

    override suspend fun insertTransaction(tx: QueuedTransactionEntity) {
        data[tx.id] = tx
    }

    override suspend fun getTransaction(id: String): QueuedTransactionEntity? = data[id]

    override suspend fun updateTransaction(tx: QueuedTransactionEntity) {
        data[tx.id] = tx
    }

    override suspend fun getPendingTransactions(now: Long): List<QueuedTransactionEntity> {
        return data.values.filter { (it.status == TransactionStatus.QUEUED.value || it.status == "PENDING") && it.ttlExpiresAt > now }
    }

    override suspend fun getBroadcastingWithSignature(): List<QueuedTransactionEntity> {
        return data.values.filter { it.status == TransactionStatus.BROADCASTING.value && !it.txSignature.isNullOrBlank() }
    }

    override suspend fun recoverStaleBroadcasting(staleThreshold: Long, now: Long) = Unit

    override suspend fun recoverStaleHandshake(staleThreshold: Long, now: Long) = Unit

    override fun observePendingTransactions(now: Long): Flow<List<QueuedTransactionEntity>> = emptyFlow()

    override fun observeTransactionsForWallet(publicKey: String): Flow<List<QueuedTransactionEntity>> = emptyFlow()

    override fun observeRecentTransactions(limit: Int): Flow<List<QueuedTransactionEntity>> = emptyFlow()

    override suspend fun updateStatus(id: String, status: String, signature: String?, attemptAt: Long) {
        val existing = data[id] ?: return
        data[id] = existing.copy(
            status = status,
            txSignature = signature ?: existing.txSignature,
            lastAttemptAt = attemptAt,
            attemptCount = existing.attemptCount + 1
        )
    }

    override suspend fun markBroadcastObserved(id: String, signature: String, attemptAt: Long) {
        val existing = data[id] ?: return
        data[id] = existing.copy(
            status = TransactionStatus.BROADCASTING.value,
            txSignature = signature,
            errorMessage = null,
            lastAttemptAt = attemptAt
        )
    }

    override suspend fun markConfirmed(id: String, signature: String, attemptAt: Long) {
        val existing = data[id] ?: return
        data[id] = existing.copy(
            status = TransactionStatus.CONFIRMED.value,
            txSignature = signature,
            errorMessage = null,
            lastAttemptAt = attemptAt
        )
    }

    override suspend fun markFailed(id: String, error: String, attemptAt: Long) {
        val existing = data[id] ?: return
        data[id] = existing.copy(
            status = TransactionStatus.FAILED.value,
            errorMessage = error,
            lastAttemptAt = attemptAt,
            attemptCount = existing.attemptCount + 1
        )
    }

    override suspend fun pruneConfirmed(olderThan: Long) = Unit

    override suspend fun expireStaleTransactions(now: Long) = Unit
}
