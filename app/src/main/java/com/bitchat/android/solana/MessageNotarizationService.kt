package com.bitchat.android.solana

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.bitchat.android.data.local.NotarizationDao
import com.bitchat.android.data.local.entities.MessageNotarizationEntity
import com.bitchat.android.data.local.entities.NotarizationStatus
import com.bitchat.android.model.BitchatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

interface NotarizationWalletGateway {
    fun getPublicKeyBase58(): String?
    fun sign(data: ByteArray): ByteArray?
}

interface NotarizationRpcGateway {
    suspend fun getLatestBlockhash(): Result<BlockhashInfo>
    suspend fun sendTransaction(signedTransactionBase64: String): Result<String>
    suspend fun confirmTransaction(signature: String): Result<Boolean>
    suspend fun getTransactionInfo(signature: String): Result<TransactionInfo>
}

/**
 * Service for notarizing BitChat messages on the Solana blockchain.
 *
 * Uses the Solana Memo Program to post SHA-256 hashes of messages on-chain,
 * providing immutable timestamping proof. Messages are batched for efficiency.
 */
@Singleton
class MessageNotarizationService internal constructor(
    private val walletGateway: NotarizationWalletGateway,
    private val rpcGateway: NotarizationRpcGateway,
    private val notarizationDao: NotarizationDao,
    private val hasInternetConnectivity: () -> Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        walletService: SolanaWalletService,
        rpcService: SolanaRpcService,
        notarizationDao: NotarizationDao
    ) : this(
        walletGateway = object : NotarizationWalletGateway {
            override fun getPublicKeyBase58(): String? = walletService.getPublicKeyBase58()
            override fun sign(data: ByteArray): ByteArray? = walletService.sign(data)
        },
        rpcGateway = object : NotarizationRpcGateway {
            override suspend fun getLatestBlockhash(): Result<BlockhashInfo> = rpcService.getLatestBlockhash()
            override suspend fun sendTransaction(signedTransactionBase64: String): Result<String> =
                rpcService.sendTransaction(signedTransactionBase64)

            override suspend fun confirmTransaction(signature: String): Result<Boolean> =
                rpcService.confirmTransaction(signature)

            override suspend fun getTransactionInfo(signature: String): Result<TransactionInfo> =
                rpcService.getTransactionInfo(signature)
        },
        notarizationDao = notarizationDao,
        hasInternetConnectivity = {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                if (network == null) {
                    false
                } else {
                    val capabilities = cm.getNetworkCapabilities(network)
                    capabilities != null &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            } catch (_: Exception) {
                false
            }
        }
    )

    companion object {
        private const val TAG = "NotarizationService"
        private const val MAX_BATCH_SIZE = 10
        private const val CONFIRMATION_DELAY_MS = 5_000L
        private const val MAX_CONFIRMATION_ATTEMPTS = 12
        private const val RECOVERY_TICK_MS = 20_000L

        // Solana Memo Program v2
        private const val MEMO_PROGRAM_ID_BASE58 = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processMutex = Mutex()
    private val activeConfirmationTxs = ConcurrentHashMap.newKeySet<String>()
    private var recoveryJob: Job? = null

    init {
        scope.launch {
            recoverInterruptedState()
            tryProcessQueued("startup")
        }
        startRecoveryTicker()
    }

    /**
     * Queue a message for blockchain notarization.
     * Computes SHA-256 hash and stores in database with QUEUED status.
     */
    suspend fun queueNotarization(message: BitchatMessage): Result<String> = withContext(Dispatchers.IO) {
        try {
            val existing = notarizationDao.getByMessageId(message.id)
            if (existing != null) {
                return@withContext Result.failure(IllegalStateException("Message already queued for notarization"))
            }

            val hashInput = "${message.sender}|${message.content}|${message.timestamp.time}"
            val messageHash = sha256Hex(hashInput)
            val contentPreview = if (message.content.length > 50) {
                message.content.take(47) + "..."
            } else {
                message.content
            }

            val entity = MessageNotarizationEntity(
                messageId = message.id,
                messageHash = messageHash,
                senderNickname = message.sender,
                contentPreview = contentPreview,
                messageTimestamp = message.timestamp.time,
                status = NotarizationStatus.QUEUED,
                createdAt = System.currentTimeMillis()
            )

            notarizationDao.insert(entity)
            Log.d(TAG, "Queued notarization: ${message.id} hash=$messageHash")

            scope.launch { tryProcessQueued("queue") }
            Result.success(messageHash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue notarization: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Process a batch of queued notarizations.
     * Builds a Memo transaction containing all hashes and broadcasts.
     */
    suspend fun processBatch(): Result<Int> = withContext(Dispatchers.IO) {
        processMutex.withLock {
            try {
                if (!hasInternetConnectivity()) {
                    return@withContext Result.success(0)
                }

                val queued = notarizationDao.getQueuedBatch(MAX_BATCH_SIZE)
                if (queued.isEmpty()) {
                    return@withContext Result.success(0)
                }

                val senderAddress = walletGateway.getPublicKeyBase58()
                    ?: return@withContext Result.failure(IllegalStateException("No wallet available"))

                val memoContent = queued.joinToString("\n") { it.messageHash }
                val batchId = UUID.randomUUID().toString()
                val messageIds = queued.map { it.messageId }

                notarizationDao.updateStatusBatch(messageIds, NotarizationStatus.BROADCASTING, batchId)

                val blockhashResult = rpcGateway.getLatestBlockhash()
                val blockhashInfo = blockhashResult.getOrNull()
                if (blockhashInfo == null) {
                    val errorMsg = blockhashResult.exceptionOrNull()?.message ?: "Cannot get blockhash"
                    notarizationDao.updateFailed(messageIds, NotarizationStatus.QUEUED, errorMsg)
                    return@withContext Result.failure(IllegalStateException(errorMsg))
                }

                val signedTxBase64 = buildMemoTransaction(
                    senderAddress = senderAddress,
                    memoContent = memoContent,
                    blockhash = blockhashInfo.blockhash
                )
                if (signedTxBase64 == null) {
                    notarizationDao.updateFailed(messageIds, NotarizationStatus.FAILED, "Failed to build transaction")
                    return@withContext Result.failure(IllegalStateException("Failed to build memo transaction"))
                }

                val sendResult = rpcGateway.sendTransaction(signedTxBase64)
                val txSignature = sendResult.getOrNull()
                if (txSignature == null) {
                    val errorMsg = sendResult.exceptionOrNull()?.message ?: "Broadcast failed"
                    val status = if (isRetryableNetworkError(errorMsg)) NotarizationStatus.QUEUED else NotarizationStatus.FAILED
                    notarizationDao.updateFailed(messageIds, status, errorMsg)
                    return@withContext Result.failure(IllegalStateException(errorMsg))
                }

                notarizationDao.updateBroadcasted(
                    messageIds = messageIds,
                    status = NotarizationStatus.BROADCASTING,
                    txSignature = txSignature,
                    batchId = batchId
                )

                Log.d(TAG, "Broadcast notarization batch: $batchId, tx=$txSignature, ${queued.size} messages")
                scope.launch { confirmBatch(messageIds, txSignature, batchId) }

                Result.success(queued.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process batch: ${e.message}")
                Result.failure(e)
            }
        }
    }

    private suspend fun confirmBatch(messageIds: List<String>, txSignature: String, batchId: String) {
        if (!activeConfirmationTxs.add(txSignature)) return
        try {
            for (attempt in 1..MAX_CONFIRMATION_ATTEMPTS) {
                delay(CONFIRMATION_DELAY_MS)
                try {
                    if (!hasInternetConnectivity()) continue
                    val confirmed = rpcGateway.confirmTransaction(txSignature).getOrDefault(false)
                    if (confirmed) {
                        val txInfo = rpcGateway.getTransactionInfo(txSignature).getOrNull()
                        notarizationDao.updateConfirmed(
                            messageIds = messageIds,
                            status = NotarizationStatus.CONFIRMED,
                            txSignature = txSignature,
                            slot = txInfo?.slot,
                            blockTime = txInfo?.blockTime,
                            batchId = batchId
                        )
                        Log.d(TAG, "Notarization confirmed: $txSignature (${messageIds.size} messages)")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Confirmation poll attempt $attempt failed: ${e.message}")
                }
            }

            notarizationDao.updateFailed(
                messageIds = messageIds,
                status = NotarizationStatus.QUEUED,
                errorMessage = "Confirmation timeout after $MAX_CONFIRMATION_ATTEMPTS attempts"
            )
            Log.w(TAG, "Notarization confirmation timed out: $txSignature")
        } finally {
            activeConfirmationTxs.remove(txSignature)
        }
    }

    suspend fun getProof(messageId: String, refreshMetadata: Boolean = true): MessageNotarizationEntity? {
        val proof = notarizationDao.getByMessageId(messageId) ?: return null
        if (!refreshMetadata) return proof
        if (proof.status != NotarizationStatus.CONFIRMED) return proof
        if (proof.txSignature.isNullOrBlank()) return proof
        if (proof.slot != null && proof.blockTime != null) return proof
        if (!hasInternetConnectivity()) return proof

        val txInfo = rpcGateway.getTransactionInfo(proof.txSignature).getOrNull() ?: return proof
        if (txInfo.slot == null && txInfo.blockTime == null) return proof

        notarizationDao.updateConfirmed(
            messageIds = listOf(proof.messageId),
            status = proof.status,
            txSignature = proof.txSignature,
            slot = txInfo.slot ?: proof.slot,
            blockTime = txInfo.blockTime ?: proof.blockTime,
            batchId = proof.batchId
        )
        return notarizationDao.getByMessageId(messageId)
    }

    fun observeNotarizations(): Flow<List<MessageNotarizationEntity>> {
        return notarizationDao.observeAll()
    }

    fun observeQueuedCount(): Flow<Int> {
        return notarizationDao.observeQueuedCount()
    }

    suspend fun retryFailed() {
        val failed = notarizationDao.getByStatus(NotarizationStatus.FAILED)
        if (failed.isNotEmpty()) {
            notarizationDao.updateStatusBatch(
                failed.map { it.messageId },
                NotarizationStatus.QUEUED,
                null
            )
            tryProcessQueued("retryFailed")
        }
    }

    private fun buildMemoTransaction(
        senderAddress: String,
        memoContent: String,
        blockhash: String
    ): String? {
        return try {
            val senderPubKey = decodeBase58(senderAddress)
            val memoProgramId = decodeBase58(MEMO_PROGRAM_ID_BASE58)
            val recentBlockhash = decodeBase58(blockhash)
            val memoData = memoContent.toByteArray(Charsets.UTF_8)

            val header = byteArrayOf(1, 0, 1)
            val accountKeys = senderPubKey + memoProgramId
            val numAccounts = byteArrayOf(2)
            val numInstructions = byteArrayOf(1)
            val programIdIndex = byteArrayOf(1)
            val numAccountIndices = byteArrayOf(1)
            val accountIndices = byteArrayOf(0)
            val dataLen = compactU16(memoData.size)

            val message = header +
                numAccounts +
                accountKeys +
                recentBlockhash +
                numInstructions +
                programIdIndex +
                numAccountIndices +
                accountIndices +
                dataLen +
                memoData

            val signature = walletGateway.sign(message) ?: return null

            val transaction = ByteArray(1 + 64 + message.size)
            transaction[0] = 1
            System.arraycopy(signature, 0, transaction, 1, 64)
            System.arraycopy(message, 0, transaction, 65, message.size)

            java.util.Base64.getEncoder().encodeToString(transaction)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build memo transaction: ${e.message}")
            null
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun compactU16(value: Int): ByteArray {
        if (value < 0x80) return byteArrayOf(value.toByte())
        if (value < 0x4000) return byteArrayOf(
            ((value and 0x7F) or 0x80).toByte(),
            ((value shr 7) and 0x7F).toByte()
        )
        return byteArrayOf(
            ((value and 0x7F) or 0x80).toByte(),
            (((value shr 7) and 0x7F) or 0x80).toByte(),
            ((value shr 14) and 0x03).toByte()
        )
    }

    private fun decodeBase58(input: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (c in input) {
            val digit = alphabet.indexOf(c)
            if (digit == -1) throw IllegalArgumentException("Invalid Base58 character: $c")
            num = num.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeros = input.takeWhile { it == '1' }.length
        val bytes = num.toByteArray()
        val stripped = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        return ByteArray(leadingZeros) + stripped
    }

    private fun startRecoveryTicker() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            while (isActive) {
                delay(RECOVERY_TICK_MS)
                recoverInterruptedState()
                tryProcessQueued("ticker")
            }
        }
    }

    private suspend fun recoverInterruptedState() {
        try {
            val recoveredCount = notarizationDao.recoverInterruptedBroadcasts()
            if (recoveredCount > 0) {
                Log.d(TAG, "Recovered $recoveredCount interrupted notarization rows to QUEUED")
            }

            val inFlight = notarizationDao.getBroadcastingWithSignature()
            inFlight
                .groupBy { "${it.batchId ?: "recovered"}|${it.txSignature ?: ""}" }
                .values
                .forEach { rows ->
                    val txSignature = rows.firstOrNull()?.txSignature ?: return@forEach
                    val batchId = rows.firstOrNull()?.batchId ?: "recovered-${txSignature.take(12)}"
                    scope.launch {
                        confirmBatch(rows.map { it.messageId }, txSignature, batchId)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to recover interrupted notarization state: ${e.message}")
        }
    }

    private suspend fun tryProcessQueued(reason: String) {
        if (!hasInternetConnectivity()) return
        val result = processBatch()
        result.onSuccess { count ->
            if (count > 0) {
                Log.d(TAG, "Processed notarization batch from $reason ($count messages)")
            }
        }
    }

    private fun isRetryableNetworkError(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("timeout") ||
            m.contains("unable to resolve host") ||
            m.contains("failed to connect") ||
            m.contains("connection reset") ||
            m.contains("network")
    }

    fun shutdown() {
        recoveryJob?.cancel()
        scope.cancel()
    }
}
