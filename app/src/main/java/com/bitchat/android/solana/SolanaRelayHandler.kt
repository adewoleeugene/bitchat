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
class SolanaRelayHandler @Inject constructor(
    private val rpcService: SolanaRpcService,
    private val transactionDao: TransactionDao
) {
    companion object {
        private const val TAG = "SolanaRelayHandler"
        private const val MAX_REQUESTS_PER_HOUR = 20
        private const val MIN_BATTERY_PERCENT = 20
        private const val REQUEST_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes
    }

    // Rate limiting: peerID -> list of timestamps
    private val peerRequestTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    // Track pending relay requests we've sent (requestId -> timestamp)
    private val pendingRequests = ConcurrentHashMap<String, Long>()

    // Track processed relay requests to avoid duplicates (requestId -> timestamp)
    private val processedRelays = ConcurrentHashMap<String, Long>()

    // Track processed intent requests to avoid duplicates (intentId -> timestamp)
    private val processedIntents = ConcurrentHashMap<String, Long>()

    // Callback for sending packets back through the mesh
    var onSendRelayReceipt: ((SolanaRelayReceipt) -> Unit)? = null

    // Callback for sending blockhash responses back through the mesh (2-step handshake)
    var onSendBlockhashResponse: ((SolanaBlockhashResponse) -> Unit)? = null

    // Callback for notifying the UI about relay events
    var onRelayEvent: ((String) -> Unit)? = null

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
        context: Context
    ): Boolean {
        // Dedup: check if we've already processed this relay request
        // Note: uses separate map from intents so a 0x32→0x30 handshake flow works
        if (processedRelays.containsKey(request.requestId)) {
            Log.d(TAG, "Already processed relay request ${request.requestId}, ignoring")
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

        processedRelays[request.requestId] = System.currentTimeMillis()
        onRelayEvent?.invoke("relaying transaction from ${fromPeerID.take(8)}...")

        // Broadcast the transaction asynchronously
        scope.launch {
            try {
                Log.d(TAG, "Broadcasting relayed transaction ${request.requestId}")

                val result = rpcService.sendTransaction(request.signedTxBase64)
                result.onSuccess { signature ->
                    Log.d(TAG, "Relay broadcast success: $signature")
                    sendReceipt(request.requestId, RelayReceiptStatus.BROADCAST, signature, "")
                    onRelayEvent?.invoke("relayed tx ${request.requestId.take(8)}... → ${signature.take(12)}...")
                }.onFailure { error ->
                    Log.e(TAG, "Relay broadcast failed: ${error.message}")
                    sendReceipt(request.requestId, RelayReceiptStatus.FAILED, "", error.message ?: "RPC error")
                    onRelayEvent?.invoke("relay failed for ${request.requestId.take(8)}...: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay error: ${e.message}", e)
                sendReceipt(request.requestId, RelayReceiptStatus.FAILED, "", e.message ?: "Unknown error")
            }
        }

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
        // Dedup (separate map from relay requests so 0x32→0x30 flow works)
        if (processedIntents.containsKey(intent.intentId)) {
            Log.d(TAG, "Already processed intent ${intent.intentId}, ignoring")
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

        processedIntents[intent.intentId] = System.currentTimeMillis()
        onRelayEvent?.invoke("fetching blockhash for ${fromPeerID.take(8)}...")

        // Fetch fresh blockhash asynchronously
        scope.launch {
            try {
                val blockhashResult = rpcService.getLatestBlockhash()
                blockhashResult.onSuccess { info ->
                    Log.d(TAG, "Sending blockhash response for intent ${intent.intentId.take(8)}...")
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
            }
        }

        return true
    }

    private fun sendBlockhashResponse(intentId: String, blockhash: String, blockHeight: Long, errorMessage: String) {
        val response = SolanaBlockhashResponse(intentId, blockhash, blockHeight, errorMessage)
        onSendBlockhashResponse?.invoke(response)
    }

    /**
     * Handle an incoming relay receipt from the mesh.
     * Called when we receive a SOLANA_TX_RECEIPT (0x31) packet.
     * Updates the local transaction status in Room DB.
     */
    fun handleRelayReceipt(receipt: SolanaRelayReceipt): Boolean {
        val pending = pendingRequests.remove(receipt.requestId) ?: return false

        val statusStr = when (receipt.status) {
            RelayReceiptStatus.BROADCAST -> "broadcast"
            RelayReceiptStatus.CONFIRMED -> "confirmed"
            else -> "failed"
        }

        // Update the transaction status in Room DB
        scope.launch {
            try {
                when (receipt.status) {
                    RelayReceiptStatus.BROADCAST, RelayReceiptStatus.CONFIRMED -> {
                        transactionDao.updateStatus(
                            receipt.requestId,
                            TransactionStatus.CONFIRMED.value,
                            receipt.txSignature
                        )
                        Log.d(TAG, "Updated tx ${receipt.requestId} status to CONFIRMED via mesh relay")
                    }
                    RelayReceiptStatus.FAILED -> {
                        // If the failure is due to relay peer conditions (not a Solana error),
                        // revert to PENDING so another peer can try relaying it
                        val isRetryable = isRetryableRelayError(receipt.errorMessage)
                        if (isRetryable) {
                            transactionDao.updateStatus(receipt.requestId, TransactionStatus.PENDING.value)
                            Log.d(TAG, "Relay failed (retryable: ${receipt.errorMessage}), tx ${receipt.requestId} reverted to PENDING")
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

        if (receipt.status == RelayReceiptStatus.FAILED) {
            val retryNote = if (isRetryableRelayError(receipt.errorMessage)) " (will retry with next peer)" else ""
            val errorSuffix = if (receipt.errorMessage.isNotEmpty()) ": ${receipt.errorMessage}" else ""
            onRelayEvent?.invoke("relay $statusStr for ${receipt.requestId.take(8)}...$errorSuffix$retryNote")
        } else {
            val sigSuffix = if (receipt.txSignature.isNotEmpty()) " (tx: ${receipt.txSignature.take(12)}...)" else ""
            onRelayEvent?.invoke("relay $statusStr for ${receipt.requestId.take(8)}...$sigSuffix")
        }

        return true
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
        processedIntents.entries.removeAll { (now - it.value) > REQUEST_EXPIRY_MS }
    }

    fun shutdown() {
        scope.cancel()
    }
}
