package com.bitchat.android.solana

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * Solana JSON-RPC client for interacting with the Solana network.
 * Handles balance queries, blockhash fetching, and transaction broadcasting.
 */
class SolanaRpcService @Inject constructor(
    private val httpClient: OkHttpClient,
    private val rpcUrl: String
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    private var requestId = 1

    // Cached blockhash for offline transaction signing
    @Volatile
    private var lastBlockhash: BlockhashInfo? = null
    @Volatile
    private var lastBlockhashTimestamp: Long = 0L

    companion object {
        // Solana blockhashes are valid for ~60-90 seconds; cache for up to 60s
        private const val BLOCKHASH_CACHE_TTL_MS = 60_000L
    }

    /**
     * Get a cached blockhash if it's still fresh enough.
     * Returns null if no cached blockhash or it's expired.
     */
    fun getCachedBlockhash(): BlockhashInfo? {
        val cached = lastBlockhash ?: return null
        val age = System.currentTimeMillis() - lastBlockhashTimestamp
        return if (age <= BLOCKHASH_CACHE_TTL_MS) cached else null
    }

    /**
     * Get balance for a Solana address in lamports.
     * 1 SOL = 1_000_000_000 lamports
     */
    suspend fun getBalance(publicKey: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall("getBalance", """["$publicKey"]""")
            val result = response.getAsJsonObject("result")
            val value = result.get("value").asLong
            Result.success(value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get a recent blockhash for transaction construction.
     * Blockhashes expire after ~60 seconds, so fetch immediately before broadcast.
     */
    suspend fun getLatestBlockhash(): Result<BlockhashInfo> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall(
                "getLatestBlockhash",
                """[{"commitment": "finalized"}]"""
            )
            val result = response.getAsJsonObject("result")
                .getAsJsonObject("value")
            val blockhash = result.get("blockhash").asString
            val lastValidBlockHeight = result.get("lastValidBlockHeight").asLong
            val info = BlockhashInfo(blockhash, lastValidBlockHeight)
            // Cache for offline use
            lastBlockhash = info
            lastBlockhashTimestamp = System.currentTimeMillis()
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send a signed transaction to the Solana network.
     * Returns the transaction signature on success.
     */
    suspend fun sendTransaction(signedTransactionBase64: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall(
                "sendTransaction",
                """["$signedTransactionBase64", {"encoding": "base64"}]"""
            )
            if (response.has("error")) {
                val error = response.getAsJsonObject("error")
                val message = error.get("message").asString
                Result.failure(SolanaRpcException(message))
            } else {
                val signature = response.get("result").asString
                Result.success(signature)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if a transaction has been confirmed on-chain.
     */
    suspend fun confirmTransaction(signature: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall(
                "getSignatureStatuses",
                """[["$signature"], {"searchTransactionHistory": true}]"""
            )
            val result = response.getAsJsonObject("result")
            val statuses = result.getAsJsonArray("value")
            if (statuses.size() > 0 && !statuses[0].isJsonNull) {
                val status = statuses[0].asJsonObject
                val confirmationStatus = status.get("confirmationStatus")?.asString
                val err = status.get("err")
                val isConfirmed = (confirmationStatus == "confirmed" || confirmationStatus == "finalized") && (err == null || err.isJsonNull)
                Result.success(isConfirmed)
            } else {
                Result.success(false)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get SPL token accounts owned by a wallet for a specific mint.
     * Returns the token balance (in smallest unit) or 0 if no account exists.
     */
    suspend fun getTokenBalance(ownerPublicKey: String, mintAddress: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall(
                "getTokenAccountsByOwner",
                """["$ownerPublicKey", {"mint": "$mintAddress"}, {"encoding": "jsonParsed"}]"""
            )
            val result = response.getAsJsonObject("result")
            val accounts = result.getAsJsonArray("value")
            if (accounts.size() > 0) {
                val account = accounts[0].asJsonObject
                val parsed = account.getAsJsonObject("account")
                    .getAsJsonObject("data")
                    .getAsJsonObject("parsed")
                    .getAsJsonObject("info")
                    .getAsJsonObject("tokenAmount")
                val amount = parsed.get("amount").asString.toLongOrNull() ?: 0L
                Result.success(amount)
            } else {
                Result.success(0L)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch current SOL price in USD from CoinGecko.
     */
    suspend fun getSolPrice(): Result<Double> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.coingecko.com/api/v3/simple/price?ids=solana&vs_currencies=usd")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.use { it.body?.string() }
                ?: return@withContext Result.failure(Exception("Empty response"))
            val json = JsonParser.parseString(body).asJsonObject
            val price = json.getAsJsonObject("solana").get("usd").asDouble
            Result.success(price)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Simple health check to verify RPC connectivity.
     */
    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(rpcUrl.replace("/rpc", "/health").let {
                    if (it.endsWith("/health")) it else "$rpcUrl/health"
                })
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun rpcCall(method: String, params: String): JsonObject {
        val id = requestId++
        val body = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
        val request = Request.Builder()
            .url(rpcUrl)
            .post(body.toRequestBody(jsonMediaType))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.use { it.body?.string() }
            ?: throw SolanaRpcException("Empty response from RPC")

        return JsonParser.parseString(responseBody).asJsonObject
    }
}

data class BlockhashInfo(
    val blockhash: String,
    val lastValidBlockHeight: Long
)

class SolanaRpcException(message: String) : Exception(message)
