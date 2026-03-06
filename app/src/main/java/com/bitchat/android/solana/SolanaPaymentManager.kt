package com.bitchat.android.solana

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.bitchat.android.data.local.TransactionDao
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.models.TransactionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Solana payment lifecycle:
 * - Queue transactions for offline-first operation
 * - Broadcast pending transactions when connectivity is available
 * - Track transaction confirmation status
 * - Auto-retry failed broadcasts
 *
 * Mirrors StoreForwardManager queue persistence pattern.
 */
@Singleton
class SolanaPaymentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletService: SolanaWalletService,
    private val rpcService: SolanaRpcService,
    private val transactionDao: TransactionDao
) {
    companion object {
        private const val TAG = "SolanaPaymentManager"
        private const val TTL_MILLIS = 24 * 60 * 60 * 1000L // 24 hours
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val LAMPORTS_PER_SOL = 1_000_000_000L
        private val RELAY_RETRY_SCHEDULE_MS = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        private const val RELAY_CLAIM_STALE_MS = 90_000L
        private const val RELAY_HARD_TIMEOUT_MS = 180_000L
        private const val RELAY_MONITOR_POLL_MS = 2_000L
        private const val RECOVERY_TICK_MS = 20_000L
        private const val CONFIRMATION_POLL_MS = 5_000L
        private const val MAX_CONFIRMATION_POLLS = 24
        private const val BALANCE_MESH_TIMEOUT_MS = 30_000L
        private const val BALANCE_MESH_STALE_MS = 90_000L
        private const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        private const val TOKEN_TRANSFER_CHECKED_INSTRUCTION_INDEX: Byte = 12
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcastJob: Job? = null
    private var recoveryJob: Job? = null
    private val relayMonitorJobs = ConcurrentHashMap<String, Job>()
    private val confirmationJobs = ConcurrentHashMap<String, Job>()
    private val relayClaimTimestamps = ConcurrentHashMap<String, Long>()
    private val relayAckTimestamps = ConcurrentHashMap<String, Long>()
    private val pendingBalanceRequests = ConcurrentHashMap<String, CompletableDeferred<Result<Long>>>()
    private val pendingBalanceRequestPublicKeys = ConcurrentHashMap<String, String>()

    // Callback for mesh relay when direct RPC is unavailable
    var onRequestMeshRelay: ((SolanaRelayRequest) -> Unit)? = null

    // Callback for 2-step handshake: send unsigned intent to request blockhash from online peer
    var onRequestBlockhashIntent: ((SolanaTransferIntent) -> Unit)? = null
    var onRequestBalanceIntent: ((SolanaBalanceIntent) -> Unit)? = null

    // Callback for posting status events to the UI (system messages in chat)
    var onStatusEvent: ((String) -> Unit)? = null

    init {
        startRecoveryTicker()
    }

    /**
     * Queue a SOL payment to a recipient.
     * Returns the queued transaction ID on success.
     */
    suspend fun queuePayment(
        recipientPublicKey: String,
        amountSol: Double,
        memo: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val senderPublicKey = walletService.getPublicKeyBase58()
                ?: return@withContext Result.failure(IllegalStateException("No wallet found. Create a wallet first."))

            val amountLamports = (amountSol * LAMPORTS_PER_SOL).toLong()
            if (amountLamports <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Amount must be greater than 0"))
            }

            val txId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val entity = QueuedTransactionEntity(
                id = txId,
                signedTransactionBase64 = "", // Will be signed at broadcast time with fresh blockhash
                senderPublicKey = senderPublicKey,
                recipientPublicKey = recipientPublicKey,
                amountLamports = amountLamports,
                assetKind = TransferAssetKind.NATIVE_SOL.name,
                assetSymbol = "SOL",
                assetDecimals = 9,
                status = TransactionStatus.QUEUED.value,
                createdAt = now,
                ttlExpiresAt = now + TTL_MILLIS
            )

            transactionDao.insertTransaction(entity)
            Log.d(TAG, "Queued payment: $amountSol SOL to $recipientPublicKey (id=$txId)")

            // Try to broadcast immediately
            tryBroadcastPending()

            Result.success(txId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue payment: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun queueSplTokenTransfer(
        recipientPublicKey: String,
        mintAddress: String,
        amountAtomic: Long,
        decimals: Int,
        symbol: String,
        memo: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val senderPublicKey = walletService.getPublicKeyBase58()
                ?: return@withContext Result.failure(IllegalStateException("No wallet found. Create a wallet first."))

            if (amountAtomic <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Amount must be greater than 0"))
            }

            val txId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val entity = QueuedTransactionEntity(
                id = txId,
                signedTransactionBase64 = "",
                senderPublicKey = senderPublicKey,
                recipientPublicKey = recipientPublicKey,
                amountLamports = amountAtomic,
                assetKind = TransferAssetKind.SPL_TOKEN.name,
                assetMintAddress = mintAddress,
                assetSymbol = symbol,
                assetDecimals = decimals,
                status = TransactionStatus.QUEUED.value,
                createdAt = now,
                ttlExpiresAt = now + TTL_MILLIS
            )

            transactionDao.insertTransaction(entity)
            Log.d(TAG, "Queued SPL transfer: $amountAtomic $symbol($mintAddress) to $recipientPublicKey (id=$txId)")
            memo?.let {
                Log.d(TAG, "Ignoring token memo until memo program support is added: $it")
            }
            tryBroadcastPending()
            Result.success(txId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue SPL transfer: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Attempt to broadcast all pending transactions.
     * Called when connectivity becomes available.
     */
    fun tryBroadcastPending() {
        if (broadcastJob?.isActive == true) return

        broadcastJob = scope.launch {
            try {
                // First expire stale transactions
                transactionDao.expireStaleTransactions()

                // Recover transactions stuck in BROADCASTING for >2 minutes (receipt was lost)
                transactionDao.recoverStaleBroadcasting()

                // Recover transactions stuck in AWAITING_BLOCKHASH for >30 seconds (no response)
                transactionDao.recoverStaleHandshake()

                val pending = transactionDao.getPendingTransactions()
                if (pending.isEmpty()) return@launch

                Log.d(TAG, "Broadcasting ${pending.size} pending transactions")

                for (tx in pending) {
                    if (tx.attemptCount >= MAX_RETRY_ATTEMPTS) {
                        transactionDao.markFailed(tx.id, "Max retries exceeded")
                        continue
                    }
                    broadcastTransaction(tx)
                    delay(1000) // Rate limit between broadcasts
                }
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast loop failed: ${e.message}", e)
            }
        }
    }

    /**
     * Request mesh relay for a pending transaction.
     * Builds and signs the transaction, then sends via Bluetooth mesh
     * for an online peer to broadcast to Solana RPC.
     */
    suspend fun requestMeshRelay(txId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val tx = transactionDao.getTransaction(txId)
                ?: return@withContext Result.failure(IllegalStateException("Transaction not found"))

            tryMeshRelayFallback(tx)
            Result.success(txId)
        } catch (e: Exception) {
            Log.e(TAG, "Mesh relay request failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Request a relayed balance lookup via nearby online mesh peers.
     * Returns the fetched lamport balance on success.
     */
    suspend fun requestBalanceViaMesh(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val publicKey = walletService.getPublicKeyBase58()
                ?: return@withContext Result.failure(IllegalStateException("No wallet found"))

            val callback = onRequestBalanceIntent
                ?: return@withContext Result.failure(IllegalStateException("Mesh balance relay not available"))

            val intentId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Result<Long>>()
            pendingBalanceRequests[intentId] = deferred
            pendingBalanceRequestPublicKeys[intentId] = publicKey

            callback(SolanaBalanceIntent(intentId = intentId, requesterPubKey = publicKey))

            val result = withTimeoutOrNull(BALANCE_MESH_TIMEOUT_MS) { deferred.await() }
                ?: Result.failure(IllegalStateException("Mesh balance request timed out"))

            pendingBalanceRequests.remove(intentId)
            pendingBalanceRequestPublicKeys.remove(intentId)
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun safeStatusEvent(message: String) {
        try {
            onStatusEvent?.invoke(message)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post status event: ${e.message}")
        }
    }

    fun handleRelayClaimObserved(claim: SolanaRelayClaim) {
        relayClaimTimestamps[claim.requestId] = System.currentTimeMillis()
        safeStatusEvent("relay claim received for ${claim.requestId.take(8)}... from ${claim.relayPeerId.take(8)}...")
    }

    fun handleRelayAckObserved(ack: SolanaRelayAck) {
        relayAckTimestamps[ack.requestId] = ack.timestampMs
        if (ack.ackType == RelayAckType.REQUEST_SEEN) {
            safeStatusEvent("mesh accepted relay request ${ack.requestId.take(8)}...")
        }
    }

    private fun startRecoveryTicker() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            while (isActive) {
                delay(RECOVERY_TICK_MS)
                reconcileBroadcastingConfirmations()
                tryBroadcastPending()
            }
        }
    }

    private suspend fun broadcastTransaction(tx: QueuedTransactionEntity) {
        try {
            val asset = tx.toTransferAsset()
            // Check connectivity FIRST — if offline, go straight to mesh relay
            // This avoids hanging on DNS/HTTP timeouts before falling back
            if (!hasInternetConnectivity()) {
                if (asset.kind == TransferAssetKind.SPL_TOKEN) {
                    Log.d(TAG, "Device offline for SPL tx ${tx.id}, keeping queued for later")
                    safeStatusEvent("offline — token transfer queued until internet is available")
                    transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
                } else {
                    Log.d(TAG, "Device offline for tx ${tx.id}, going directly to mesh relay")
                    safeStatusEvent("offline — routing payment via mesh relay...")
                    transactionDao.updateStatus(tx.id, TransactionStatus.BROADCASTING.value)
                    tryMeshRelayFallback(tx)
                }
                return
            }

            // Update status to broadcasting
            transactionDao.updateStatus(tx.id, TransactionStatus.BROADCASTING.value)

            if (asset.kind == TransferAssetKind.NATIVE_SOL) {
                val balanceResult = rpcService.getBalance(tx.senderPublicKey)
                val balance = balanceResult.getOrNull()
                if (balance == null) {
                    Log.w(TAG, "Cannot check balance for tx ${tx.id} (RPC unreachable), trying mesh relay")
                    tryMeshRelayFallback(tx)
                    return
                }
                if (balance < tx.amountLamports + 5000) {
                    transactionDao.markFailed(tx.id, "Insufficient balance (need ${tx.amountLamports + 5000} lamports, have $balance)")
                    return
                }
            } else {
                val tokenBalance = rpcService.getTokenBalance(
                    ownerPublicKey = tx.senderPublicKey,
                    mintAddress = asset.mintAddress.orEmpty()
                ).getOrNull()
                if (tokenBalance == null) {
                    transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
                    return
                }
                if (tokenBalance < tx.amountLamports) {
                    transactionDao.markFailed(
                        tx.id,
                        "Insufficient ${asset.symbol} balance (need ${tx.amountLamports}, have $tokenBalance)"
                    )
                    return
                }
            }

            // Get fresh blockhash
            val blockhashResult = rpcService.getLatestBlockhash()
            val blockhashInfo = blockhashResult.getOrNull()
            if (blockhashInfo == null) {
                if (asset.kind == TransferAssetKind.NATIVE_SOL) {
                    Log.w(TAG, "Failed to get blockhash for tx ${tx.id} (RPC unreachable), trying mesh relay")
                    tryMeshRelayFallback(tx)
                } else {
                    transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
                }
                return
            }

            // Build and sign the transaction
            val signedTxBase64 = when (asset.kind) {
                TransferAssetKind.NATIVE_SOL -> buildAndSignTransaction(
                    senderPublicKey = tx.senderPublicKey,
                    recipientPublicKey = tx.recipientPublicKey,
                    amountLamports = tx.amountLamports,
                    blockhash = blockhashInfo.blockhash
                )
                TransferAssetKind.SPL_TOKEN -> buildAndSignSplTokenTransaction(
                    ownerPublicKey = tx.senderPublicKey,
                    recipientOwnerPublicKey = tx.recipientPublicKey,
                    mintAddress = asset.mintAddress.orEmpty(),
                    amountAtomic = tx.amountLamports,
                    decimals = asset.decimals,
                    blockhash = blockhashInfo.blockhash
                )
            }

            if (signedTxBase64 == null) {
                transactionDao.markFailed(tx.id, "Failed to sign transaction")
                return
            }

            // Send to network
            Log.d(TAG, "Broadcasting tx ${tx.id}: ${tx.amountLamports} ${asset.symbol} units to ${tx.recipientPublicKey}")
            val sendResult = rpcService.sendTransaction(signedTxBase64)
            sendResult.onSuccess { signature ->
                transactionDao.markBroadcastObserved(tx.id, signature)
                Log.d(TAG, "Transaction ${tx.id} broadcasted. waiting confirmation: $signature")
                safeStatusEvent("payment broadcasted (${tx.id.take(8)}...) waiting confirmation...")
                startConfirmationMonitor(tx.id, signature, source = "direct-rpc")
            }.onFailure { error ->
                val errorMsg = error.message ?: "Unknown RPC error"
                if (errorMsg.contains("Blockhash not found") || errorMsg.contains("expired")) {
                    // Blockhash expired, retry with fresh one
                    transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
                    Log.w(TAG, "Blockhash expired for tx ${tx.id}, will retry")
                } else if (asset.kind == TransferAssetKind.NATIVE_SOL &&
                    (errorMsg.contains("timeout") || errorMsg.contains("Unable to resolve host") || errorMsg.contains("connect"))
                ) {
                    // Network error — try mesh relay
                    Log.w(TAG, "RPC send failed for tx ${tx.id} (connectivity issue), trying mesh relay")
                    tryMeshRelayFallback(tx)
                } else {
                    transactionDao.markFailed(tx.id, errorMsg)
                    Log.e(TAG, "Transaction ${tx.id} failed: $errorMsg")
                }
            }
        } catch (e: Exception) {
            // Network exceptions (UnknownHostException, SocketTimeoutException, etc.) — try mesh relay
            val msg = e.message ?: "Unknown error"
            if (tx.assetKind == TransferAssetKind.NATIVE_SOL.name &&
                (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
                e is java.net.ConnectException || msg.contains("timeout") || msg.contains("connect"))
            ) {
                Log.w(TAG, "Broadcast error for tx ${tx.id} (connectivity issue), trying mesh relay: $msg")
                tryMeshRelayFallback(tx)
            } else {
                transactionDao.markFailed(tx.id, msg)
                Log.e(TAG, "Broadcast error for tx ${tx.id}: $msg", e)
            }
        }
    }

    private fun hasInternetConnectivity(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    /**
     * Fall back to mesh relay when direct RPC is unavailable.
     * Uses cached blockhash if available (fast path), otherwise initiates
     * 2-step handshake to request a fresh blockhash from an online peer.
     */
    private suspend fun tryMeshRelayFallback(tx: QueuedTransactionEntity) {
        Log.d(TAG, ">>> tryMeshRelayFallback for tx ${tx.id}")

        val relayCallback = onRequestMeshRelay
        if (relayCallback == null) {
            transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
            Log.w(TAG, ">>> BLOCKED: No mesh relay callback (onRequestMeshRelay=null) for tx ${tx.id}, keeping as pending")
            safeStatusEvent("mesh relay not available — payment saved, will retry")
            return
        }
        Log.d(TAG, ">>> onRequestMeshRelay callback is set")

        // Try cached blockhash first (avoids unnecessary RPC call when offline)
        val cachedBlockhash = rpcService.getCachedBlockhash()
        if (cachedBlockhash != null) {
            Log.d(TAG, ">>> Using cached blockhash for mesh relay of tx ${tx.id}")
            safeStatusEvent("signing with cached blockhash, sending via mesh...")
            signAndRelayTransaction(tx, cachedBlockhash.blockhash, relayCallback)
            return
        }

        // No cached blockhash — try fresh RPC only if we might have connectivity
        // (skip if we already know we're offline to avoid 30s OkHttp timeout)
        if (hasInternetConnectivity()) {
            val blockhashResult = rpcService.getLatestBlockhash()
            val freshBlockhash = blockhashResult.getOrNull()
            if (freshBlockhash != null) {
                Log.d(TAG, ">>> Using fresh blockhash for mesh relay of tx ${tx.id}")
                signAndRelayTransaction(tx, freshBlockhash.blockhash, relayCallback)
                return
            }
        } else {
            Log.d(TAG, ">>> Skipping RPC blockhash fetch (offline), going to 2-step handshake")
        }

        // Truly offline with no cached blockhash — initiate 2-step handshake
        Log.d(TAG, ">>> No blockhash available, initiating 2-step mesh handshake for tx ${tx.id}")
        safeStatusEvent("requesting blockhash from mesh peers...")
        requestBlockhashViaMesh(tx)
    }

    /**
     * Sign a transaction with the given blockhash and send it via mesh relay.
     */
    private suspend fun signAndRelayTransaction(
        tx: QueuedTransactionEntity,
        blockhash: String,
        relayCallback: (SolanaRelayRequest) -> Unit
    ) {
        Log.d(TAG, ">>> signAndRelayTransaction: signing tx ${tx.id} with blockhash ${blockhash.take(12)}...")
        val signedTxBase64 = buildAndSignTransaction(
            senderPublicKey = tx.senderPublicKey,
            recipientPublicKey = tx.recipientPublicKey,
            amountLamports = tx.amountLamports,
            blockhash = blockhash
        )

        if (signedTxBase64 == null) {
            Log.e(TAG, ">>> FAILED: Could not sign transaction ${tx.id}")
            transactionDao.markFailed(tx.id, "Failed to sign transaction for mesh relay")
            return
        }

        val request = SolanaRelayRequest(
            requestId = tx.id,
            signedTxBase64 = signedTxBase64,
            senderPubKey = tx.senderPublicKey
        )

        // Persist signed payload so retries remain deterministic for this requestId.
        transactionDao.updateTransaction(tx.copy(signedTransactionBase64 = signedTxBase64))
        transactionDao.updateStatus(tx.id, TransactionStatus.BROADCASTING.value)
        Log.d(TAG, ">>> Invoking mesh relay callback for tx ${tx.id} (signed tx size: ${signedTxBase64.length} chars)")
        safeStatusEvent("signed tx sent to mesh for relay broadcast")
        relayCallback(request)
        startRelayMonitor(request, relayCallback)
        Log.d(TAG, ">>> Mesh relay callback invoked successfully for tx ${tx.id}")
    }

    private fun startRelayMonitor(
        request: SolanaRelayRequest,
        relayCallback: (SolanaRelayRequest) -> Unit
    ) {
        relayMonitorJobs.remove(request.requestId)?.cancel()
        relayMonitorJobs[request.requestId] = scope.launch {
            val startedAt = System.currentTimeMillis()
            var retryIndex = 0
            var nextRetryAt = startedAt + RELAY_RETRY_SCHEDULE_MS.first()

            while (isActive) {
                delay(RELAY_MONITOR_POLL_MS)

                val tx = transactionDao.getTransaction(request.requestId) ?: break
                if (tx.status != TransactionStatus.BROADCASTING.value) break

                val now = System.currentTimeMillis()
                val claimAt = relayClaimTimestamps[request.requestId]
                val hasFreshClaim = claimAt != null && (now - claimAt) <= RELAY_CLAIM_STALE_MS

                if ((now - startedAt) > RELAY_HARD_TIMEOUT_MS) {
                    transactionDao.updateStatus(request.requestId, TransactionStatus.QUEUED.value)
                    safeStatusEvent("relay timed out for ${request.requestId.take(8)}... queued for retry")
                    break
                }

                if (hasFreshClaim) continue

                if (retryIndex < RELAY_RETRY_SCHEDULE_MS.size && now >= nextRetryAt) {
                    safeStatusEvent(
                        "no relay claim yet for ${request.requestId.take(8)}... retrying (${retryIndex + 1}/${RELAY_RETRY_SCHEDULE_MS.size})"
                    )
                    relayCallback(request)
                    val delayMs = RELAY_RETRY_SCHEDULE_MS[retryIndex]
                    retryIndex += 1
                    nextRetryAt = now + delayMs
                }
            }

            relayMonitorJobs.remove(request.requestId)
            relayClaimTimestamps.remove(request.requestId)
            relayAckTimestamps.remove(request.requestId)
        }
    }

    private suspend fun reconcileBroadcastingConfirmations() {
        try {
            if (!hasInternetConnectivity()) return
            val broadcasting = transactionDao.getBroadcastingWithSignature()
            for (tx in broadcasting) {
                val signature = tx.txSignature ?: continue
                startConfirmationMonitor(tx.id, signature, source = "recovery")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reconcile broadcasting confirmations: ${e.message}")
        }
    }

    private fun startConfirmationMonitor(txId: String, signature: String, source: String) {
        val existing = confirmationJobs[txId]
        if (existing?.isActive == true) return

        confirmationJobs[txId] = scope.launch {
            try {
                repeat(MAX_CONFIRMATION_POLLS) { _ ->
                    delay(CONFIRMATION_POLL_MS)
                    val tx = transactionDao.getTransaction(txId) ?: return@launch
                    if (tx.status != TransactionStatus.BROADCASTING.value) return@launch

                    if (!hasInternetConnectivity()) return@repeat
                    val confirmed = rpcService.confirmTransaction(signature).getOrElse { false }
                    if (confirmed) {
                        transactionDao.markConfirmed(txId, signature)
                        safeStatusEvent("payment confirmed (${txId.take(8)}...)")
                        try {
                            walletService.refreshBalance()
                        } catch (_: Exception) {
                        }
                        Log.d(TAG, "Transaction $txId confirmed on-chain via $source")
                        return@launch
                    }
                }
                Log.d(TAG, "Confirmation monitor timed out for $txId (kept as BROADCASTING)")
            } catch (e: Exception) {
                Log.w(TAG, "Confirmation monitor failed for $txId: ${e.message}")
            } finally {
                confirmationJobs.remove(txId)
            }
        }
    }

    /**
     * 2-step handshake step 1: Send unsigned transfer intent through mesh
     * to request a fresh blockhash from an online peer.
     */
    private suspend fun requestBlockhashViaMesh(tx: QueuedTransactionEntity) {
        Log.d(TAG, ">>> requestBlockhashViaMesh for tx ${tx.id}")
        val intentCallback = onRequestBlockhashIntent
        if (intentCallback == null) {
            transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
            Log.w(TAG, ">>> BLOCKED: No mesh intent callback (onRequestBlockhashIntent=null) for tx ${tx.id}, keeping as pending")
            return
        }

        val senderPubKey = walletService.getPublicKeyBase58()
        if (senderPubKey == null) {
            transactionDao.markFailed(tx.id, "No wallet for mesh handshake")
            return
        }

        val intent = SolanaTransferIntent(
            intentId = tx.id,
            senderPubKey = senderPubKey,
            recipientPubKey = tx.recipientPublicKey,
            amountLamports = tx.amountLamports
        )

        transactionDao.updateStatus(tx.id, TransactionStatus.AWAITING_BLOCKHASH.value)
        Log.d(TAG, "Sending transfer intent for tx ${tx.id} via mesh (2-step handshake)")
        intentCallback(intent)
    }

    /**
     * 2-step handshake step 2: Handle blockhash response from online peer.
     * Signs the transaction with the fresh blockhash and sends via mesh relay.
     */
    suspend fun handleBlockhashResponse(response: SolanaBlockhashResponse) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, ">>> handleBlockhashResponse received for intent ${response.intentId.take(8)}... blockhash=${response.blockhash.take(12).ifEmpty { "(empty)" }} error=${response.errorMessage.ifEmpty { "(none)" }}")

            val tx = transactionDao.getTransaction(response.intentId)
            if (tx == null) {
                Log.w(TAG, ">>> No transaction found for blockhash response ${response.intentId}")
                return@withContext
            }

            // Guard: only process if we're still waiting for a blockhash
            // (another peer may have already responded)
            if (tx.status != TransactionStatus.AWAITING_BLOCKHASH.value) {
                Log.d(TAG, ">>> Ignoring blockhash response for tx ${tx.id} in state ${tx.status}")
                return@withContext
            }

            if (response.errorMessage.isNotEmpty()) {
                // Move back to QUEUED so retry loop can request another gateway quickly.
                Log.w(TAG, "Blockhash request failed: ${response.errorMessage}, reverting tx ${tx.id} to QUEUED")
                safeStatusEvent("blockhash request failed, retrying with next mesh peer...")
                transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
                return@withContext
            }

            if (response.blockhash.isEmpty()) {
                Log.w(TAG, "Empty blockhash in response for tx ${tx.id}, reverting to QUEUED")
                safeStatusEvent("received empty blockhash, retrying...")
                transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
                return@withContext
            }

            Log.d(TAG, ">>> Received fresh blockhash for tx ${tx.id}, signing and relaying")
            safeStatusEvent("received blockhash from mesh peer, signing tx...")
            val relayCallback = onRequestMeshRelay
            if (relayCallback == null) {
                transactionDao.updateStatus(tx.id, TransactionStatus.QUEUED.value)
                Log.w(TAG, "No mesh relay callback for tx ${tx.id} after receiving blockhash")
                return@withContext
            }

            signAndRelayTransaction(tx, response.blockhash, relayCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle blockhash response: ${e.message}", e)
        }
    }

    /**
     * Handle relayed balance response from mesh peers.
     */
    suspend fun handleBalanceResponse(response: SolanaBalanceResponse) = withContext(Dispatchers.IO) {
        val deferred = pendingBalanceRequests[response.intentId] ?: return@withContext
        val expectedPubKey = pendingBalanceRequestPublicKeys[response.intentId]
        if (expectedPubKey != null && response.walletPubKey != expectedPubKey) {
            deferred.complete(Result.failure(IllegalStateException("Mismatched wallet in balance response")))
            pendingBalanceRequests.remove(response.intentId)
            pendingBalanceRequestPublicKeys.remove(response.intentId)
            return@withContext
        }

        if (response.errorMessage.isNotEmpty()) {
            deferred.complete(Result.failure(IllegalStateException(response.errorMessage)))
            pendingBalanceRequests.remove(response.intentId)
            pendingBalanceRequestPublicKeys.remove(response.intentId)
            return@withContext
        }

        val ageMs = System.currentTimeMillis() - response.fetchedAtMs
        if (response.fetchedAtMs > 0 && ageMs > BALANCE_MESH_STALE_MS) {
            deferred.complete(Result.failure(IllegalStateException("Stale mesh balance response")))
            pendingBalanceRequests.remove(response.intentId)
            pendingBalanceRequestPublicKeys.remove(response.intentId)
            return@withContext
        }

        val cacheResult = walletService.updateCachedBalanceFromMesh(
            lamports = response.lamports,
            updatedAtMs = response.fetchedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        if (cacheResult.isFailure) {
            deferred.complete(Result.failure(cacheResult.exceptionOrNull() ?: IllegalStateException("Failed to cache mesh balance")))
            pendingBalanceRequests.remove(response.intentId)
            pendingBalanceRequestPublicKeys.remove(response.intentId)
            return@withContext
        }
        deferred.complete(Result.success(response.lamports))
        pendingBalanceRequests.remove(response.intentId)
        pendingBalanceRequestPublicKeys.remove(response.intentId)
    }

    /**
     * Build a Solana transfer transaction and sign it.
     * Returns base64-encoded signed transaction.
     */
    private fun buildAndSignTransaction(
        senderPublicKey: String,
        recipientPublicKey: String,
        amountLamports: Long,
        blockhash: String
    ): String? {
        return try {
            val senderPubKeyBytes = decodeBase58(senderPublicKey)
            val recipientPubKeyBytes = decodeBase58(recipientPublicKey)
            val recentBlockhash = decodeBase58(blockhash)

            // Build System Program Transfer instruction
            // Program ID: 11111111111111111111111111111111 (System Program)
            val systemProgramId = ByteArray(32) // All zeros = System Program

            // Transfer instruction data: [2, 0, 0, 0] + amount as LE u64
            val instructionData = ByteArray(12)
            instructionData[0] = 2 // Transfer instruction index
            // Amount in little-endian u64
            for (i in 0..7) {
                instructionData[4 + i] = ((amountLamports shr (i * 8)) and 0xFF).toByte()
            }

            // Build the transaction message (legacy format)
            val message = buildTransactionMessage(
                senderPubKey = senderPubKeyBytes,
                recipientPubKey = recipientPubKeyBytes,
                systemProgramId = systemProgramId,
                recentBlockhash = recentBlockhash,
                instructionData = instructionData
            )

            // Sign the message
            val signature = walletService.sign(message) ?: return null

            // Assemble the full transaction: [sig_count] [signature] [message]
            val transaction = ByteArray(1 + 64 + message.size)
            transaction[0] = 1 // One signature
            System.arraycopy(signature, 0, transaction, 1, 64)
            System.arraycopy(message, 0, transaction, 65, message.size)

            android.util.Base64.encodeToString(transaction, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build transaction: ${e.message}", e)
            null
        }
    }

    private suspend fun buildAndSignSplTokenTransaction(
        ownerPublicKey: String,
        recipientOwnerPublicKey: String,
        mintAddress: String,
        amountAtomic: Long,
        decimals: Int,
        blockhash: String
    ): String? {
        return try {
            val sourceTokenAccount = rpcService.getTokenAccountAddress(ownerPublicKey, mintAddress)
                .getOrNull()
                ?: return null
            val destinationTokenAccount = rpcService.getTokenAccountAddress(recipientOwnerPublicKey, mintAddress)
                .getOrNull()
                ?: return null

            val ownerPubKeyBytes = decodeBase58(ownerPublicKey)
            val sourceTokenAccountBytes = decodeBase58(sourceTokenAccount)
            val mintBytes = decodeBase58(mintAddress)
            val destinationTokenAccountBytes = decodeBase58(destinationTokenAccount)
            val tokenProgramBytes = decodeBase58(TOKEN_PROGRAM_ID)
            val recentBlockhash = decodeBase58(blockhash)

            val instructionData = ByteArray(10)
            instructionData[0] = TOKEN_TRANSFER_CHECKED_INSTRUCTION_INDEX
            for (i in 0..7) {
                instructionData[1 + i] = ((amountAtomic shr (i * 8)) and 0xFF).toByte()
            }
            instructionData[9] = decimals.toByte()

            val message = buildSplTokenTransferMessage(
                ownerPubKey = ownerPubKeyBytes,
                sourceTokenAccount = sourceTokenAccountBytes,
                mintPubKey = mintBytes,
                destinationTokenAccount = destinationTokenAccountBytes,
                tokenProgramId = tokenProgramBytes,
                recentBlockhash = recentBlockhash,
                instructionData = instructionData
            )

            val signature = walletService.sign(message) ?: return null
            val transaction = ByteArray(1 + 64 + message.size)
            transaction[0] = 1
            System.arraycopy(signature, 0, transaction, 1, 64)
            System.arraycopy(message, 0, transaction, 65, message.size)
            android.util.Base64.encodeToString(transaction, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build SPL token transaction: ${e.message}", e)
            null
        }
    }

    /**
     * Build Solana legacy transaction message bytes.
     */
    private fun buildTransactionMessage(
        senderPubKey: ByteArray,
        recipientPubKey: ByteArray,
        systemProgramId: ByteArray,
        recentBlockhash: ByteArray,
        instructionData: ByteArray
    ): ByteArray {
        // Message header: [num_required_signatures, num_readonly_signed, num_readonly_unsigned]
        val header = byteArrayOf(1, 0, 1)

        // Account keys: sender (signer, writable), recipient (writable), system program (readonly)
        val accountKeys = senderPubKey + recipientPubKey + systemProgramId

        // Compact array length encoding for 3 accounts
        val numAccounts = byteArrayOf(3)

        // Instruction: program_id_index=2, account_indices=[0,1], data=instructionData
        val numInstructions = byteArrayOf(1) // 1 instruction
        val programIdIndex = byteArrayOf(2) // System Program is at index 2
        val numAccountIndices = byteArrayOf(2) // 2 account indices
        val accountIndices = byteArrayOf(0, 1) // sender=0, recipient=1
        val dataLen = compactU16(instructionData.size)

        // Assemble message
        return header +
                numAccounts +
                accountKeys +
                recentBlockhash +
                numInstructions +
                programIdIndex +
                numAccountIndices +
                accountIndices +
                dataLen +
                instructionData
    }

    private fun buildSplTokenTransferMessage(
        ownerPubKey: ByteArray,
        sourceTokenAccount: ByteArray,
        mintPubKey: ByteArray,
        destinationTokenAccount: ByteArray,
        tokenProgramId: ByteArray,
        recentBlockhash: ByteArray,
        instructionData: ByteArray
    ): ByteArray {
        val header = byteArrayOf(1, 0, 2)
        val accountKeys =
            ownerPubKey +
                sourceTokenAccount +
                mintPubKey +
                destinationTokenAccount +
                tokenProgramId
        val numAccounts = byteArrayOf(5)
        val numInstructions = byteArrayOf(1)
        val programIdIndex = byteArrayOf(4)
        val numAccountIndices = byteArrayOf(4)
        val accountIndices = byteArrayOf(1, 2, 3, 0)
        val dataLen = compactU16(instructionData.size)

        return header +
            numAccounts +
            accountKeys +
            recentBlockhash +
            numInstructions +
            programIdIndex +
            numAccountIndices +
            accountIndices +
            dataLen +
            instructionData
    }

    /**
     * Compact u16 encoding (Solana uses this for array lengths in serialized messages).
     */
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

    /**
     * Observe recent transactions for UI display.
     */
    fun observeRecentTransactions(): Flow<List<QueuedTransactionEntity>> {
        return transactionDao.observeRecentTransactions()
    }

    /**
     * Observe pending transactions count.
     */
    fun observePendingTransactions(): Flow<List<QueuedTransactionEntity>> {
        return transactionDao.observePendingTransactions()
    }

    /**
     * Convert lamports to display-friendly SOL string.
     */
    fun lamportsToSolDisplay(lamports: Long): String {
        return walletService.lamportsToSol(lamports)
    }

    fun formatDisplayAmount(tx: QueuedTransactionEntity): String {
        return TransferAmountFormatter.formatForDisplay(tx.amountLamports, tx.toTransferAsset())
    }

    private fun QueuedTransactionEntity.toTransferAsset(): TransferAsset {
        val kind = runCatching { TransferAssetKind.valueOf(assetKind) }
            .getOrDefault(TransferAssetKind.NATIVE_SOL)
        return TransferAsset(
            kind = kind,
            mintAddress = assetMintAddress,
            symbol = assetSymbol,
            decimals = assetDecimals
        )
    }

    /**
     * Clean up old confirmed transactions (older than 7 days).
     */
    suspend fun pruneOldTransactions() {
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        transactionDao.pruneConfirmed(sevenDaysAgo)
    }

    /**
     * Decode Base58 string to bytes.
     */
    private fun decodeBase58(input: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)

        for (c in input) {
            val digit = alphabet.indexOf(c)
            if (digit == -1) throw IllegalArgumentException("Invalid Base58 character: $c")
            num = num.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }

        // Count leading '1's (zero bytes)
        val leadingZeros = input.takeWhile { it == '1' }.length

        val bytes = num.toByteArray()
        // BigInteger may add a leading zero byte for positive numbers
        val stripped = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes

        val raw = ByteArray(leadingZeros) + stripped
        // Pad to 32 bytes if this is a pubkey/blockhash (shorter means leading zeros were lost)
        return if (raw.size < 32) ByteArray(32 - raw.size) + raw else raw
    }

    fun shutdown() {
        broadcastJob?.cancel()
        recoveryJob?.cancel()
        relayMonitorJobs.values.forEach { it.cancel() }
        relayMonitorJobs.clear()
        confirmationJobs.values.forEach { it.cancel() }
        confirmationJobs.clear()
        pendingBalanceRequests.values.forEach { it.cancel() }
        pendingBalanceRequests.clear()
        pendingBalanceRequestPublicKeys.clear()
        scope.cancel()
    }
}
