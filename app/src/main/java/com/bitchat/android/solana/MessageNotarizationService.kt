package com.bitchat.android.solana

import android.util.Log
import com.bitchat.android.data.local.NotarizationDao
import com.bitchat.android.data.local.entities.MessageNotarizationEntity
import com.bitchat.android.data.local.entities.NotarizationStatus
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for notarizing BitChat messages on the Solana blockchain.
 *
 * Uses the Solana Memo Program to post SHA-256 hashes of messages on-chain,
 * providing immutable timestamping proof. Messages are batched for efficiency.
 *
 * Flow:
 * 1. queueNotarization() - hash message, insert QUEUED record
 * 2. processBatch() - collect queued hashes, build memo tx, broadcast
 * 3. confirmBatch() - verify on-chain confirmation, update proofs
 */
@Singleton
class MessageNotarizationService @Inject constructor(
    private val walletService: SolanaWalletService,
    private val rpcService: SolanaRpcService,
    private val notarizationDao: NotarizationDao
) {
    companion object {
        private const val TAG = "NotarizationService"
        private const val MAX_BATCH_SIZE = 10
        private const val CONFIRMATION_DELAY_MS = 5_000L
        private const val MAX_CONFIRMATION_ATTEMPTS = 12

        // Solana Memo Program v2
        private const val MEMO_PROGRAM_ID_BASE58 = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Queue a message for blockchain notarization.
     * Computes SHA-256 hash and stores in database with QUEUED status.
     */
    suspend fun queueNotarization(message: BitchatMessage): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Check if already notarized
            val existing = notarizationDao.getByMessageId(message.id)
            if (existing != null) {
                return@withContext Result.failure(IllegalStateException("Message already queued for notarization"))
            }

            // Compute SHA-256 hash of (sender|content|timestamp)
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

            // Attempt immediate batch processing
            scope.launch { processBatch() }

            Result.success(messageHash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue notarization: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Process a batch of queued notarizations.
     * Builds a Memo transaction containing all hashes and broadcasts.
     */
    suspend fun processBatch(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val queued = notarizationDao.getQueuedBatch(MAX_BATCH_SIZE)
            if (queued.isEmpty()) {
                return@withContext Result.success(0)
            }

            val senderAddress = walletService.getPublicKeyBase58()
                ?: return@withContext Result.failure(IllegalStateException("No wallet available"))

            // Build memo content: newline-separated hashes
            val memoContent = queued.joinToString("\n") { it.messageHash }
            val batchId = UUID.randomUUID().toString()
            val messageIds = queued.map { it.messageId }

            // Update status to BROADCASTING
            notarizationDao.updateStatusBatch(messageIds, NotarizationStatus.BROADCASTING, batchId)

            // Get a fresh blockhash
            val blockhashResult = rpcService.getLatestBlockhash()
            val blockhashInfo = blockhashResult.getOrNull()
            if (blockhashInfo == null) {
                notarizationDao.updateFailed(messageIds, NotarizationStatus.QUEUED, null)
                return@withContext Result.failure(IllegalStateException("Cannot get blockhash"))
            }

            // Build and sign the Memo transaction
            val signedTxBase64 = buildMemoTransaction(
                senderAddress = senderAddress,
                memoContent = memoContent,
                blockhash = blockhashInfo.blockhash
            )
            if (signedTxBase64 == null) {
                notarizationDao.updateFailed(messageIds, NotarizationStatus.FAILED, "Failed to build transaction")
                return@withContext Result.failure(IllegalStateException("Failed to build memo transaction"))
            }

            // Broadcast
            val sendResult = rpcService.sendTransaction(signedTxBase64)
            val txSignature = sendResult.getOrNull()
            if (txSignature == null) {
                val errorMsg = sendResult.exceptionOrNull()?.message ?: "Broadcast failed"
                notarizationDao.updateFailed(messageIds, NotarizationStatus.FAILED, errorMsg)
                return@withContext Result.failure(IllegalStateException(errorMsg))
            }

            Log.d(TAG, "Broadcast notarization batch: $batchId, tx=$txSignature, ${queued.size} messages")

            // Start confirmation polling
            scope.launch { confirmBatch(messageIds, txSignature, batchId) }

            Result.success(queued.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process batch: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Poll for on-chain confirmation and update records with proof data.
     */
    private suspend fun confirmBatch(messageIds: List<String>, txSignature: String, batchId: String) {
        for (attempt in 1..MAX_CONFIRMATION_ATTEMPTS) {
            delay(CONFIRMATION_DELAY_MS)
            try {
                val confirmed = rpcService.confirmTransaction(txSignature).getOrDefault(false)
                if (confirmed) {
                    // Fetch transaction details for slot/block time
                    val slot = getTransactionSlot(txSignature)
                    notarizationDao.updateConfirmed(
                        messageIds = messageIds,
                        status = NotarizationStatus.CONFIRMED,
                        txSignature = txSignature,
                        slot = slot,
                        blockTime = null, // Block time fetched separately if needed
                        batchId = batchId
                    )
                    Log.d(TAG, "Notarization confirmed: $txSignature (${messageIds.size} messages)")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Confirmation poll attempt $attempt failed: ${e.message}")
            }
        }

        // Exhausted retries
        notarizationDao.updateFailed(
            messageIds = messageIds,
            status = NotarizationStatus.FAILED,
            errorMessage = "Confirmation timeout after $MAX_CONFIRMATION_ATTEMPTS attempts"
        )
        Log.w(TAG, "Notarization confirmation timed out: $txSignature")
    }

    /**
     * Get the notarization proof for a specific message.
     */
    suspend fun getProof(messageId: String): MessageNotarizationEntity? {
        return notarizationDao.getByMessageId(messageId)
    }

    /**
     * Observe all notarization records.
     */
    fun observeNotarizations(): Flow<List<MessageNotarizationEntity>> {
        return notarizationDao.observeAll()
    }

    /**
     * Observe count of queued notarizations.
     */
    fun observeQueuedCount(): Flow<Int> {
        return notarizationDao.observeQueuedCount()
    }

    /**
     * Retry failed notarizations by resetting them to QUEUED.
     */
    suspend fun retryFailed() {
        val failed = notarizationDao.getByStatus(NotarizationStatus.FAILED)
        if (failed.isNotEmpty()) {
            notarizationDao.updateStatusBatch(
                failed.map { it.messageId },
                NotarizationStatus.QUEUED,
                null
            )
            processBatch()
        }
    }

    // ---- Transaction Building ----

    /**
     * Build a Solana legacy transaction with a Memo program instruction.
     */
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

            // Message header: [num_required_signatures, num_readonly_signed, num_readonly_unsigned]
            val header = byteArrayOf(1, 0, 1)

            // Account keys: sender (signer, writable), memo program (readonly)
            val accountKeys = senderPubKey + memoProgramId

            // Compact array length for 2 accounts
            val numAccounts = byteArrayOf(2)

            // Instruction: program_id_index=1 (memo program), accounts=[0] (signer), data=memoData
            val numInstructions = byteArrayOf(1)
            val programIdIndex = byteArrayOf(1) // Memo Program at index 1
            val numAccountIndices = byteArrayOf(1)
            val accountIndices = byteArrayOf(0) // signer=0
            val dataLen = compactU16(memoData.size)

            // Assemble message
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

            // Sign the message
            val signature = walletService.sign(message) ?: return null

            // Assemble full transaction: [sig_count] [signature] [message]
            val transaction = ByteArray(1 + 64 + message.size)
            transaction[0] = 1 // One signature
            System.arraycopy(signature, 0, transaction, 1, 64)
            System.arraycopy(message, 0, transaction, 65, message.size)

            android.util.Base64.encodeToString(transaction, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build memo transaction: ${e.message}", e)
            null
        }
    }

    /**
     * Attempt to get the slot for a confirmed transaction.
     */
    private suspend fun getTransactionSlot(signature: String): Long? {
        return try {
            // The confirmTransaction RPC already checks status; slot info is
            // available from getSignatureStatuses but we keep it simple.
            // A full implementation would call getTransaction for block details.
            null
        } catch (_: Exception) {
            null
        }
    }

    // ---- Utility Methods ----

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

    fun shutdown() {
        scope.cancel()
    }
}
