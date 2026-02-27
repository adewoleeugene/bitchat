package com.bitchat.android.solana

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import com.bitchat.android.data.local.TransactionDao
import com.bitchat.android.data.models.TransactionStatus
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

interface RelayRpcGateway {
    suspend fun sendTransaction(signedTxBase64: String): Result<String>
    suspend fun getLatestBlockhash(): Result<BlockhashInfo>
    suspend fun confirmTransaction(signature: String): Result<Boolean>
    suspend fun getBalance(address: String): Result<Long>
    suspend fun getCurrentSlot(): Result<Long>
}

/**
 * Handles Solana transaction relay over the Bluetooth mesh network.
 *
 * When an offline user wants to broadcast a Solana transaction, they send a
 * SOLANA_TX_RELAY (0x30) packet through the mesh. Online peers receive it,
 * validate the request, broadcast the transaction to Solana RPC, and send
 * back a SOLANA_TX_RECEIPT (0x31) with the result.
 *
 * Security: Rate limited to MAX_REQUESTS_PER_HOUR per peer, requires WiFi
 * by default, and minimum battery threshold.
 */
@Singleton
class SolanaRelayHandler internal constructor(
    private val rpcGateway: RelayRpcGateway,
    private val transactionDao: TransactionDao
) {
    @Inject
    constructor(
        rpcService: SolanaRpcService,
        transactionDao: TransactionDao
    ) : this(
        rpcGateway = object : RelayRpcGateway {
            override suspend fun sendTransaction(signedTxBase64: String): Result<String> =
                rpcService.sendTransaction(signedTxBase64)

            override suspend fun getLatestBlockhash(): Result<BlockhashInfo> =
                rpcService.getLatestBlockhash()

            override suspend fun confirmTransaction(signature: String): Result<Boolean> =
                rpcService.confirmTransaction(signature)

            override suspend fun getBalance(address: String): Result<Long> =
                rpcService.getBalance(address)

            override suspend fun getCurrentSlot(): Result<Long> =
                rpcService.getCurrentSlot()
        },
        transactionDao = transactionDao
    )

    companion object {
        private const val TAG = "SolanaRelayHandler"
        private const val MAX_REQUESTS_PER_HOUR = 20
        private const val MIN_BATTERY_PERCENT = 20
        private const val REQUEST_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes
        private const val CLAIM_TTL_MS = 90_000L
        private const val INTENT_DEDUP_WINDOW_MS = 20_000L
        private const val CONFIRMATION_POLL_MS = 5_000L
        private const val MAX_CONFIRMATION_POLLS = 24
    }

    private data class RelayClaimLock(
        val relayPeerId: String,
        val expiresAtMs: Long
    )

    // Rate limiting: peerID -> list of timestamps
    private val peerRequestTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    // Track pending relay requests we've sent (requestId -> timestamp)
    private val pendingRequests = ConcurrentHashMap<String, Long>()

    // Track processed relay requests to avoid duplicates (requestId -> timestamp)
    private val processedRelays = ConcurrentHashMap<String, Long>()

    // Track processed intent requests to avoid duplicates (intentId -> timestamp)
    private val processedIntents = ConcurrentHashMap<String, Long>()
    private val inflightIntents = ConcurrentHashMap<String, Long>()
    private val processedBalanceIntents = ConcurrentHashMap<String, Long>()
    private val inflightBalanceIntents = ConcurrentHashMap<String, Long>()

    // Track relay ownership claims to prevent multiple gateways processing same request
    private val relayClaims = ConcurrentHashMap<String, RelayClaimLock>()

    // Callback for sending packets back through the mesh
    var onSendRelayReceipt: ((SolanaRelayReceipt) -> Unit)? = null

    // Callback for sending blockhash responses back through the mesh (2-step handshake)
    var onSendBlockhashResponse: ((SolanaBlockhashResponse) -> Unit)? = null
    var onSendBalanceResponse: ((SolanaBalanceResponse) -> Unit)? = null

    // Callback for sending relay ownership claims through the mesh
    var onSendRelayClaim: ((SolanaRelayClaim) -> Unit)? = null

    // Callback for sending relay ACKs through the mesh
    var onSendRelayAck: ((SolanaRelayAck) -> Unit)? = null

    // Callback for notifying the UI about relay events
    var onRelayEvent: ((String) -> Unit)? = null

    // Callback when a claim is observed for a request we're tracking
    var onClaimObserved: ((SolanaRelayClaim) -> Unit)? = null

    // Callback when a relay ACK is observed for a request we're tracking
    var onAckObserved: ((SolanaRelayAck) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Create a relay request for a pending transaction.
     * Called by the offline user who wants their tx broadcast.
     */
    fun createRelayRequest(signedTxBase64: String, senderPubKey: String): SolanaRelayRequest {
        val requestId = UUID.randomUUID().toString()
        pendingRequests[requestId] = System.currentTimeMillis()
        return SolanaRelayRequest(requestId, signedTxBase64, senderPubKey)
    }

    /**
     * Track that we sent a relay request so we can match the receipt when it arrives.
     * Called when a relay request is broadcast via mesh.
     */
    fun trackOutgoingRequest(requestId: String) {
        pendingRequests[requestId] = System.currentTimeMillis()
    }

    /**
     * Handle an incoming relay request from the mesh.
     * Called when we receive a SOLANA_TX_RELAY (0x30) packet.
     * Returns true if we accepted and will process it.
     */
    fun handleRelayRequest(
        request: SolanaRelayRequest,
        fromPeerID: String,
        localPeerID: String,
        context: Context
    ): Boolean {
        cleanup()
        sendAck(request.requestId, RelayAckType.REQUEST_SEEN, localPeerID)

        // Dedup: check if we've already processed this relay request
        // Note: uses separate map from intents so a 0x32→0x30 handshake flow works
        if (processedRelays.containsKey(request.requestId)) {
            Log.d(TAG, "Already processed relay request ${request.requestId}, ignoring")
            return false
        }

        // Honor active claim locks from other peers.
        val existingClaim = relayClaims[request.requestId]
        if (existingClaim != null && existingClaim.expiresAtMs > System.currentTimeMillis() &&
            existingClaim.relayPeerId != localPeerID) {
            Log.d(TAG, "Request ${request.requestId.take(8)}... already claimed by ${existingClaim.relayPeerId.take(8)}..., skipping")
            return false
        }

        // Rate limiting
        if (!checkRateLimit(fromPeerID)) {
            Log.w(TAG, "Rate limit exceeded for peer $fromPeerID")
            sendReceipt(request.requestId, RelayReceiptStatus.FAILED, "", "Rate limit exceeded")
            return false
        }

        // Battery check
        if (!hasSufficientBattery(context)) {
            Log.d(TAG, "Battery too low for relay")
            sendReceipt(request.requestId, RelayReceiptStatus.FAILED, "", "Relay peer battery too low")
            return false
        }

        // Connectivity check
        if (!hasInternetConnectivity(context)) {
            Log.d(TAG, "No internet connectivity for relay")
            sendReceipt(request.requestId, RelayReceiptStatus.FAILED, "", "Relay peer has no internet")
            return false
        }

        // Claim ownership before processing to reduce duplicate gateway broadcasts.
        val claim = SolanaRelayClaim(
            requestId = request.requestId,
            relayPeerId = localPeerID,
            claimExpiresAtMs = System.currentTimeMillis() + CLAIM_TTL_MS
        )
        relayClaims[request.requestId] = RelayClaimLock(claim.relayPeerId, claim.claimExpiresAtMs)
        onSendRelayClaim?.invoke(claim)
        onClaimObserved?.invoke(claim)
        sendAck(request.requestId, RelayAckType.CLAIM_SEEN, localPeerID)

        processedRelays[request.requestId] = System.currentTimeMillis()
        onRelayEvent?.invoke("relaying transaction from ${fromPeerID.take(8)}...")

        // Broadcast the transaction asynchronously
        scope.launch {
            try {
                Log.d(TAG, "Broadcasting relayed transaction ${request.requestId}")

                val result = rpcGateway.sendTransaction(request.signedTxBase64)
                result.onSuccess { signature ->
                    Log.d(TAG, "Relay broadcast success: $signature")
                    sendReceipt(request.requestId, RelayReceiptStatus.BROADCAST, signature, "")
                    onRelayEvent?.invoke("relayed tx ${request.requestId.take(8)}... → ${signature.take(12)}...")
                    scope.launch {
                        confirmAndSendReceipt(request.requestId, signature)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Relay broadcast failed: ${error.message}")
                    sendReceipt(request.requestId, RelayReceiptStatus.FAILED, "", error.message ?: "RPC error")
                    onRelayEvent?.invoke("relay failed for ${request.requestId.take(8)}...: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay error: ${e.message}", e)
                sendReceipt(request.requestId, RelayReceiptStatus.FAILED, "", e.message ?: "Unknown error")
            } finally {
                relayClaims.remove(request.requestId)
            }
        }

        return true
    }

    /**
     * Track relay ownership claim packets (0x34) observed in mesh.
     */
    fun handleRelayClaim(claim: SolanaRelayClaim, fromPeerID: String): Boolean {
        if (claim.claimExpiresAtMs <= System.currentTimeMillis()) return false
        relayClaims[claim.requestId] = RelayClaimLock(claim.relayPeerId, claim.claimExpiresAtMs)
        onClaimObserved?.invoke(claim)
        Log.d(TAG, "Observed relay claim for ${claim.requestId.take(8)}... by ${claim.relayPeerId.take(8)}... (from=${fromPeerID.take(8)}...)")
        return true
    }

    /**
     * Track relay ACK packets (0x35) observed in mesh.
     */
    fun handleRelayAck(ack: SolanaRelayAck, fromPeerID: String): Boolean {
        onAckObserved?.invoke(ack)
        Log.d(TAG, "Observed relay ack for ${ack.requestId.take(8)}... type=${ack.ackType} from=${fromPeerID.take(8)}...")
        return true
    }

    /**
     * Handle an incoming transfer intent from an offline peer (0x32).
     * Step 1 of 2-step handshake: fetch a fresh blockhash from Solana RPC
     * and send it back through the mesh so the offline user can sign.
     */
    fun handleIntentRequest(
        intent: SolanaTransferIntent,
        fromPeerID: String,
        context: Context
    ): Boolean {
        cleanup()

        // Dedup while request is in flight on this peer.
        if (inflightIntents.containsKey(intent.intentId)) {
            Log.d(TAG, "Already processed intent ${intent.intentId}, ignoring")
            return false
        }

        // Short post-success dedup window to avoid broadcast storms, while still allowing fast retries.
        val recentProcessedAt = processedIntents[intent.intentId]
        if (recentProcessedAt != null && (System.currentTimeMillis() - recentProcessedAt) < INTENT_DEDUP_WINDOW_MS) {
            Log.d(TAG, "Ignoring duplicate recent intent ${intent.intentId} within dedup window")
            return false
        }

        // Rate limiting (shares quota with relay requests)
        if (!checkRateLimit(fromPeerID)) {
            Log.w(TAG, "Rate limit exceeded for intent from $fromPeerID")
            sendBlockhashResponse(intent.intentId, "", 0, "Rate limit exceeded")
            return false
        }

        // Battery check
        if (!hasSufficientBattery(context)) {
            Log.d(TAG, "Battery too low for intent relay")
            sendBlockhashResponse(intent.intentId, "", 0, "Relay peer battery too low")
            return false
        }

        // Connectivity check
        if (!hasInternetConnectivity(context)) {
            Log.d(TAG, "No internet connectivity for intent relay")
            sendBlockhashResponse(intent.intentId, "", 0, "Relay peer has no internet")
            return false
        }

        inflightIntents[intent.intentId] = System.currentTimeMillis()
        onRelayEvent?.invoke("fetching blockhash for ${fromPeerID.take(8)}...")

        // Fetch fresh blockhash asynchronously
        scope.launch {
            try {
                val blockhashResult = rpcGateway.getLatestBlockhash()
                blockhashResult.onSuccess { info ->
                    Log.d(TAG, "Sending blockhash response for intent ${intent.intentId.take(8)}...")
                    processedIntents[intent.intentId] = System.currentTimeMillis()
                    sendBlockhashResponse(intent.intentId, info.blockhash, info.lastValidBlockHeight, "")
                    onRelayEvent?.invoke("sent blockhash to ${fromPeerID.take(8)}...")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to fetch blockhash for intent: ${error.message}")
                    sendBlockhashResponse(intent.intentId, "", 0, error.message ?: "RPC error")
                    onRelayEvent?.invoke("blockhash fetch failed for ${fromPeerID.take(8)}...: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Intent relay error: ${e.message}", e)
                sendBlockhashResponse(intent.intentId, "", 0, e.message ?: "Unknown error")
            } finally {
                inflightIntents.remove(intent.intentId)
            }
        }

        return true
    }

    /**
     * Handle incoming balance intent from an offline peer (0x36).
     * Online peer queries RPC and sends back a balance response (0x37).
     */
    fun handleBalanceIntent(
        intent: SolanaBalanceIntent,
        fromPeerID: String,
        context: Context
    ): Boolean {
        cleanup()

        if (inflightBalanceIntents.containsKey(intent.intentId)) {
            Log.d(TAG, "Already processing balance intent ${intent.intentId}, ignoring duplicate")
            return false
        }

        val recentProcessedAt = processedBalanceIntents[intent.intentId]
        if (recentProcessedAt != null && (System.currentTimeMillis() - recentProcessedAt) < INTENT_DEDUP_WINDOW_MS) {
            Log.d(TAG, "Ignoring duplicate recent balance intent ${intent.intentId} within dedup window")
            return false
        }

        if (!checkRateLimit(fromPeerID)) {
            sendBalanceResponse(intent.intentId, intent.requesterPubKey, 0L, 0L, "Rate limit exceeded")
            return false
        }

        if (!hasSufficientBattery(context)) {
            sendBalanceResponse(intent.intentId, intent.requesterPubKey, 0L, 0L, "Relay peer battery too low")
            return false
        }

        if (!hasInternetConnectivity(context)) {
            sendBalanceResponse(intent.intentId, intent.requesterPubKey, 0L, 0L, "Relay peer has no internet")
            return false
        }

        inflightBalanceIntents[intent.intentId] = System.currentTimeMillis()

        scope.launch {
            try {
                val balanceResult = rpcGateway.getBalance(intent.requesterPubKey)
                balanceResult.onSuccess { lamports ->
                    val slot = rpcGateway.getCurrentSlot().getOrElse { 0L }
                    processedBalanceIntents[intent.intentId] = System.currentTimeMillis()
                    sendBalanceResponse(
                        intentId = intent.intentId,
                        walletPubKey = intent.requesterPubKey,
                        lamports = lamports,
                        slot = slot,
                        errorMessage = ""
                    )
                }.onFailure { error ->
                    sendBalanceResponse(
                        intentId = intent.intentId,
                        walletPubKey = intent.requesterPubKey,
                        lamports = 0L,
                        slot = 0L,
                        errorMessage = error.message ?: "RPC error"
                    )
                }
            } catch (e: Exception) {
                sendBalanceResponse(
                    intentId = intent.intentId,
                    walletPubKey = intent.requesterPubKey,
                    lamports = 0L,
                    slot = 0L,
                    errorMessage = e.message ?: "Unknown error"
                )
            } finally {
                inflightBalanceIntents.remove(intent.intentId)
            }
        }

        return true
    }

    private fun sendBlockhashResponse(intentId: String, blockhash: String, blockHeight: Long, errorMessage: String) {
        val response = SolanaBlockhashResponse(intentId, blockhash, blockHeight, errorMessage)
        onSendBlockhashResponse?.invoke(response)
    }

    private fun sendBalanceResponse(
        intentId: String,
        walletPubKey: String,
        lamports: Long,
        slot: Long,
        errorMessage: String
    ) {
        onSendBalanceResponse?.invoke(
            SolanaBalanceResponse(
                intentId = intentId,
                walletPubKey = walletPubKey,
                lamports = lamports,
                slot = slot,
                fetchedAtMs = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
        )
    }

    /**
     * Handle an incoming relay receipt from the mesh.
     * Called when we receive a SOLANA_TX_RECEIPT (0x31) packet.
     * Updates the local transaction status in Room DB.
     */
    fun handleRelayReceipt(receipt: SolanaRelayReceipt): Boolean {
        val trackedAt = pendingRequests[receipt.requestId]
        if (trackedAt == null) {
            Log.d(
                TAG,
                "Processing late/untracked relay receipt for ${receipt.requestId.take(8)}... " +
                    "(status=${receipt.status})"
            )
        }

        val statusStr = when (receipt.status) {
            RelayReceiptStatus.BROADCAST -> "broadcast"
            RelayReceiptStatus.CONFIRMED -> "confirmed"
            else -> "failed"
        }
        val isRetryableFailure = receipt.status == RelayReceiptStatus.FAILED && isRetryableRelayError(receipt.errorMessage)

        // Update the transaction status in Room DB
        scope.launch {
            try {
                when (receipt.status) {
                    RelayReceiptStatus.BROADCAST -> {
                        if (receipt.txSignature.isNotEmpty()) {
                            transactionDao.markBroadcastObserved(receipt.requestId, receipt.txSignature)
                            Log.d(TAG, "Updated tx ${receipt.requestId} status to BROADCASTING via mesh relay")
                        } else {
                            transactionDao.updateStatus(receipt.requestId, TransactionStatus.BROADCASTING.value)
                            Log.d(TAG, "Updated tx ${receipt.requestId} status to BROADCASTING via mesh relay (no signature)")
                        }
                    }
                    RelayReceiptStatus.CONFIRMED -> {
                        val signature = receipt.txSignature
                        if (signature.isNotEmpty()) {
                            transactionDao.markConfirmed(receipt.requestId, signature)
                        } else {
                            transactionDao.updateStatus(receipt.requestId, TransactionStatus.CONFIRMED.value)
                        }
                        Log.d(TAG, "Updated tx ${receipt.requestId} status to CONFIRMED via mesh relay")
                    }
                    RelayReceiptStatus.FAILED -> {
                        // If the failure is due to relay peer conditions (not a Solana error),
                        // revert to QUEUED so another peer can try relaying it
                        if (isRetryableFailure) {
                            transactionDao.updateStatus(receipt.requestId, TransactionStatus.QUEUED.value)
                            Log.d(TAG, "Relay failed (retryable: ${receipt.errorMessage}), tx ${receipt.requestId} reverted to QUEUED")
                        } else {
                            transactionDao.markFailed(receipt.requestId, receipt.errorMessage.ifEmpty { "Relay failed" })
                            Log.d(TAG, "Updated tx ${receipt.requestId} status to FAILED via mesh relay")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update tx status from relay receipt: ${e.message}")
            }
        }

        if (isRetryableFailure && trackedAt != null) {
            // Keep request tracked for additional relay attempts and subsequent success receipts.
            pendingRequests[receipt.requestId] = trackedAt
        } else {
            pendingRequests.remove(receipt.requestId)
            relayClaims.remove(receipt.requestId)
        }

        if (receipt.status == RelayReceiptStatus.FAILED) {
            val retryNote = if (isRetryableFailure) " (will retry with next peer)" else ""
            val errorSuffix = if (receipt.errorMessage.isNotEmpty()) ": ${receipt.errorMessage}" else ""
            onRelayEvent?.invoke("relay $statusStr for ${receipt.requestId.take(8)}...$errorSuffix$retryNote")
        } else {
            val sigSuffix = if (receipt.txSignature.isNotEmpty()) " (tx: ${receipt.txSignature.take(12)}...)" else ""
            onRelayEvent?.invoke("relay $statusStr for ${receipt.requestId.take(8)}...$sigSuffix")
        }

        return true
    }

    private suspend fun confirmAndSendReceipt(requestId: String, signature: String) {
        try {
            repeat(MAX_CONFIRMATION_POLLS) {
                delay(CONFIRMATION_POLL_MS)
                val confirmed = rpcGateway.confirmTransaction(signature).getOrElse { false }
                if (confirmed) {
                    sendReceipt(requestId, RelayReceiptStatus.CONFIRMED, signature, "")
                    onRelayEvent?.invoke("relay confirmed for ${requestId.take(8)}... (tx: ${signature.take(12)}...)")
                    return
                }
            }
            Log.d(TAG, "Relay confirmation poll timed out for ${requestId.take(8)}... (tx: ${signature.take(12)}...)")
        } catch (e: Exception) {
            Log.w(TAG, "Relay confirmation poll failed for ${requestId.take(8)}...: ${e.message}")
        }
    }

    /**
     * Determine if a relay error is retryable (relay peer issue, not a Solana rejection).
     * Retryable errors: peer had no internet, low battery, rate limited, timeout.
     * Non-retryable: Solana RPC rejected the transaction (bad sig, insufficient funds, etc.)
     */
    private fun isRetryableRelayError(errorMessage: String): Boolean {
        val retryablePatterns = listOf(
            "no internet", "battery too low", "Rate limit",
            "timeout", "connect", "Unable to resolve host"
        )
        return retryablePatterns.any { errorMessage.contains(it, ignoreCase = true) }
    }

    /**
     * Check if we have a pending relay request (i.e., we're waiting for a receipt).
     */
    fun hasPendingRequest(requestId: String): Boolean {
        return pendingRequests.containsKey(requestId)
    }

    private fun sendReceipt(requestId: String, status: Byte, txSignature: String, errorMessage: String) {
        val receipt = SolanaRelayReceipt(requestId, status, txSignature, errorMessage)
        onSendRelayReceipt?.invoke(receipt)
    }

    private fun sendAck(requestId: String, type: Byte, localPeerID: String) {
        onSendRelayAck?.invoke(
            SolanaRelayAck(
                requestId = requestId,
                ackType = type,
                peerId = localPeerID
            )
        )
    }

    private fun checkRateLimit(peerID: String): Boolean {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3600_000L

        val timestamps = peerRequestTimestamps.getOrPut(peerID) { mutableListOf() }
        timestamps.removeAll { it < oneHourAgo }
        if (timestamps.size >= MAX_REQUESTS_PER_HOUR) return false
        timestamps.add(now)
        return true
    }

    private fun hasSufficientBattery(context: Context): Boolean {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            level >= MIN_BATTERY_PERCENT
        } catch (_: Exception) { true }
    }

    private fun hasInternetConnectivity(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) { false }
    }

    /**
     * Clean up expired pending requests and processed cache.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        pendingRequests.entries.removeAll { (now - it.value) > REQUEST_EXPIRY_MS }
        processedRelays.entries.removeAll { (now - it.value) > REQUEST_EXPIRY_MS }
        processedIntents.entries.removeAll { (now - it.value) > INTENT_DEDUP_WINDOW_MS }
        inflightIntents.entries.removeAll { (now - it.value) > REQUEST_EXPIRY_MS }
        processedBalanceIntents.entries.removeAll { (now - it.value) > INTENT_DEDUP_WINDOW_MS }
        inflightBalanceIntents.entries.removeAll { (now - it.value) > REQUEST_EXPIRY_MS }
        relayClaims.entries.removeAll { it.value.expiresAtMs <= now }
    }

    fun shutdown() {
        scope.cancel()
    }
}
