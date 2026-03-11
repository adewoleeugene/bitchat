package com.bitchat.android.solana

import com.google.gson.Gson
import com.google.gson.JsonArray
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
    @Volatile
    private var cachedNftApiSupport: Boolean? = null
    @Volatile
    private var cachedNftApiSupportCheckedAt: Long = 0L

    companion object {
        // Solana blockhashes are valid for ~60-90 seconds; cache for up to 60s
        private const val BLOCKHASH_CACHE_TTL_MS = 60_000L
        // Capability probe cache TTLs: keep true longer, recheck false sooner.
        private const val NFT_API_SUPPORT_TTL_MS = 10 * 60_000L
        private const val NFT_API_UNSUPPORTED_TTL_MS = 60_000L
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
     * Get the current slot from RPC.
     */
    suspend fun getCurrentSlot(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall("getSlot", """[]""")
            val slot = response.get("result").asLong
            Result.success(slot)
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
     * Fetch transaction metadata for a signature.
     * Returns slot and blockTime (if available).
     */
    suspend fun getTransactionInfo(signature: String): Result<TransactionInfo> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall(
                "getTransaction",
                """["$signature", {"encoding":"json","commitment":"confirmed","maxSupportedTransactionVersion":0}]"""
            )
            val result = response.getAsJsonObject("result")
                ?: return@withContext Result.success(TransactionInfo(slot = null, blockTime = null))
            val slot = result.get("slot")?.asLong
            val blockTime = result.get("blockTime")?.asLong
            Result.success(TransactionInfo(slot = slot, blockTime = blockTime))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAccountInfoBase64(address: String): Result<AccountInfoBase64> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall(
                "getAccountInfo",
                """["$address", {"encoding":"base64","commitment":"confirmed"}]"""
            )
            val result = response.getAsJsonObject("result")
            val value = result?.getAsJsonObject("value")
                ?: return@withContext Result.failure(IllegalStateException("account_not_found"))
            val dataArray = value.getAsJsonArray("data")
            val dataBase64 = dataArray?.get(0)?.asString
                ?: return@withContext Result.failure(IllegalStateException("account_data_unavailable"))
            val slot = result.getAsJsonObject("context")?.get("slot")?.asLong
            Result.success(AccountInfoBase64(dataBase64 = dataBase64, slot = slot))
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
            if (accounts.size() == 0) return@withContext Result.success(0L)

            var total = 0L
            for (accountEl in accounts) {
                if (!accountEl.isJsonObject) continue
                val tokenAmount = accountEl.asJsonObject
                    .getAsJsonObject("account")
                    ?.getAsJsonObject("data")
                    ?.getAsJsonObject("parsed")
                    ?.getAsJsonObject("info")
                    ?.getAsJsonObject("tokenAmount")
                    ?: continue

                val amount = tokenAmount.get("amount")?.asString?.toLongOrNull() ?: 0L
                total = try {
                    Math.addExact(total, amount)
                } catch (_: ArithmeticException) {
                    Long.MAX_VALUE
                }
            }
            Result.success(total)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the first token account owned by a wallet for a specific mint.
     * Returns null when the owner has no account for that mint.
     */
    suspend fun getTokenAccountAddress(ownerPublicKey: String, mintAddress: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall(
                "getTokenAccountsByOwner",
                """["$ownerPublicKey", {"mint": "$mintAddress"}, {"encoding": "jsonParsed"}]"""
            )
            val result = response.getAsJsonObject("result")
            val accounts = result.getAsJsonArray("value")
            if (accounts.size() == 0) {
                return@withContext Result.success(null)
            }

            val pubkey = accounts.get(0)?.asJsonObject?.get("pubkey")?.asString
            Result.success(pubkey)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if an owner holds any NFT from a specific collection mint.
     * Strategy:
     * 1) Fast path via DAS getAssetsByOwner.
     * 2) Fallback: standard getTokenAccountsByOwner (Token Program) to enumerate owned NFT-like mints,
     *    then DAS getAsset per mint to inspect collection grouping.
     */
    suspend fun hasNftFromCollection(ownerPublicKey: String, collectionMintAddress: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val normalizedCollection = collectionMintAddress.trim()

            // 1) DAS fast path
            val dasOwnerResult = hasNftFromCollectionViaDasOwner(ownerPublicKey, normalizedCollection)
            dasOwnerResult.onSuccess { found ->
                if (found) return@withContext Result.success(true)
            }

            // If DAS owner call failed due unsupported method/provider, attempt fallback path.
            val ownedNftLikeMints = getOwnedNftLikeMints(ownerPublicKey).getOrElse { error ->
                return@withContext Result.failure<Boolean>(error)
            }

            if (ownedNftLikeMints.isEmpty()) {
                return@withContext Result.success(false)
            }

            var sawAssetApiFailure = false
            for (mint in ownedNftLikeMints) {
                val collectionKeyResult = getAssetCollectionKey(mint)
                if (collectionKeyResult.isFailure) {
                    sawAssetApiFailure = true
                    continue
                }
                val collectionKey = collectionKeyResult.getOrNull() ?: continue

                if (collectionKey == normalizedCollection) {
                    return@withContext Result.success(true)
                }
            }

            if (sawAssetApiFailure) {
                return@withContext Result.failure<Boolean>(
                    SolanaRpcException(
                        "RPC provider lacks required NFT APIs (DAS). Configure an RPC endpoint with DAS support for collection gates."
                    )
                )
            }

            Result.success(false)
        } catch (e: Exception) {
            Result.failure<Boolean>(e)
        }
    }

    /**
     * Check whether the configured RPC appears to support the NFT APIs required
     * for collection gates. Cached after first successful probe.
     */
    suspend fun supportsNftCollectionGates(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedNftApiSupport
        if (cached != null) {
            val age = now - cachedNftApiSupportCheckedAt
            val ttl = if (cached) NFT_API_SUPPORT_TTL_MS else NFT_API_UNSUPPORTED_TTL_MS
            if (age in 0..ttl) {
                return@withContext cached
            }
        }
        val supported = try {
            // Probe DAS getAsset existence with a known public key string.
            // "Asset not found" still means method exists; "method not found" means unsupported.
            val response = rpcCall("getAsset", """{"id":"11111111111111111111111111111111"}""")
            if (!response.has("error")) {
                true
            } else {
                val msg = response.getAsJsonObject("error").get("message")?.asString?.lowercase() ?: ""
                !(msg.contains("method not found") || msg.contains("unsupported"))
            }
        } catch (_: Exception) {
            false
        }
        cachedNftApiSupport = supported
        cachedNftApiSupportCheckedAt = now
        supported
    }

    private fun hasNftFromCollectionViaDasOwner(
        ownerPublicKey: String,
        normalizedCollection: String
    ): Result<Boolean> {
        return try {
            var page = 1
            val limit = 100
            var hasMatch = false

            while (!hasMatch) {
                val params = """
                    {"ownerAddress":"$ownerPublicKey","page":$page,"limit":$limit}
                """.trimIndent()
                val response = rpcCall("getAssetsByOwner", params)

                if (response.has("error")) {
                    val message = response.getAsJsonObject("error").get("message")?.asString
                        ?: "DAS getAssetsByOwner failed"
                    return Result.failure(SolanaRpcException(message))
                }

                val result = response.getAsJsonObject("result")
                    ?: return Result.failure(SolanaRpcException("Missing result in DAS response"))
                val items = result.getAsJsonArray("items") ?: JsonArray()

                if (items.size() == 0) break

                for (itemElement in items) {
                    if (!itemElement.isJsonObject) continue
                    val item = itemElement.asJsonObject
                    val grouping = item.getAsJsonArray("grouping") ?: continue

                    val matches = grouping.any { groupEl ->
                        if (!groupEl.isJsonObject) return@any false
                        val group = groupEl.asJsonObject
                        val key = group.get("group_key")?.asString ?: return@any false
                        val value = group.get("group_value")?.asString ?: return@any false
                        key == "collection" && value == normalizedCollection
                    }
                    if (matches) {
                        hasMatch = true
                        break
                    }
                }

                val total = result.get("total")?.asInt ?: 0
                if (items.size() < limit || page * limit >= total) break
                page++
            }

            Result.success(hasMatch)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getOwnedNftLikeMints(ownerPublicKey: String): Result<List<String>> {
        return try {
            val mints = LinkedHashSet<String>()

            val tokenProgramIds = listOf(
                // SPL Token Program
                "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                // Token-2022 Program
                "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
            )

            for (programId in tokenProgramIds) {
                val response = rpcCall(
                    "getTokenAccountsByOwner",
                    """["$ownerPublicKey", {"programId":"$programId"}, {"encoding":"jsonParsed"}]"""
                )
                val result = response.getAsJsonObject("result") ?: continue
                val accounts = result.getAsJsonArray("value") ?: continue

                for (accountEl in accounts) {
                    if (!accountEl.isJsonObject) continue
                    val info = accountEl.asJsonObject
                        .getAsJsonObject("account")
                        ?.getAsJsonObject("data")
                        ?.getAsJsonObject("parsed")
                        ?.getAsJsonObject("info")
                        ?: continue
                    val mint = info.get("mint")?.asString ?: continue
                    val tokenAmount = info.getAsJsonObject("tokenAmount") ?: continue
                    val amount = tokenAmount.get("amount")?.asString?.toLongOrNull() ?: 0L
                    val decimals = tokenAmount.get("decimals")?.asInt ?: 0

                    // NFT-like heuristic for token-account fallback: non-zero balance and 0 decimals.
                    if (amount > 0 && decimals == 0) {
                        mints.add(mint)
                    }
                }
            }

            Result.success(mints.toList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getAssetCollectionKey(mintAddress: String): Result<String?> {
        return try {
            val response = rpcCall("getAsset", """{"id":"$mintAddress"}""")
            if (response.has("error")) {
                val message = response.getAsJsonObject("error").get("message")?.asString
                    ?: "DAS getAsset failed"
                return Result.failure(SolanaRpcException(message))
            }

            val result = response.getAsJsonObject("result") ?: return Result.success(null)
            val grouping = result.getAsJsonArray("grouping") ?: JsonArray()
            val collectionKey = grouping.firstOrNull { el ->
                el.isJsonObject && el.asJsonObject.get("group_key")?.asString == "collection"
            }?.asJsonObject?.get("group_value")?.asString

            Result.success(collectionKey)
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

    /**
     * Fetch NFT image URI from DAS getAsset content.links.image.
     * Returns the direct image URL, or the json_uri as fallback.
     */
    suspend fun getNftImageUri(mintAddress: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val response = rpcCall("getAsset", """{"id":"$mintAddress"}""")
            if (response.has("error")) return@withContext Result.success(null)
            val result = response.getAsJsonObject("result") ?: return@withContext Result.success(null)
            val content = result.getAsJsonObject("content") ?: return@withContext Result.success(null)
            val links = content.getAsJsonObject("links")
            val imageUrl = links?.get("image")?.asString
            if (!imageUrl.isNullOrBlank()) return@withContext Result.success(imageUrl)
            // Fallback to json_uri (caller can fetch JSON for image field)
            val jsonUri = content.get("json_uri")?.asString
            Result.success(jsonUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch owned NFTs with image URIs for avatar selection.
     * Returns list of NftInfo(mintAddress, name, imageUri). Capped at 100 items.
     */
    suspend fun getOwnedNftsWithImages(ownerPublicKey: String): Result<List<NftInfo>> = withContext(Dispatchers.IO) {
        try {
            val nfts = mutableListOf<NftInfo>()
            var page = 1
            val limit = 50

            while (nfts.size < 100) {
                val response = rpcCall(
                    "getAssetsByOwner",
                    """{"ownerAddress":"$ownerPublicKey","page":$page,"limit":$limit}"""
                )
                if (response.has("error")) break
                val result = response.getAsJsonObject("result") ?: break
                val items = result.getAsJsonArray("items") ?: break
                if (items.size() == 0) break

                for (item in items) {
                    if (!item.isJsonObject) continue
                    val obj = item.asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    val content = obj.getAsJsonObject("content") ?: continue
                    val links = content.getAsJsonObject("links")
                    val imageUrl = links?.get("image")?.asString
                    val metadata = content.getAsJsonObject("metadata")
                    val name = metadata?.get("name")?.asString ?: "Unnamed NFT"
                    if (!imageUrl.isNullOrBlank()) {
                        nfts.add(NftInfo(id, name, imageUrl))
                    }
                }

                val total = result.get("total")?.asInt ?: 0
                if (items.size() < limit || page * limit >= total) break
                page++
            }
            Result.success(nfts)
        } catch (e: Exception) {
            Result.failure(e)
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

data class AccountInfoBase64(
    val dataBase64: String,
    val slot: Long?
)

data class NftInfo(
    val mintAddress: String,
    val name: String,
    val imageUri: String
)

data class BlockhashInfo(
    val blockhash: String,
    val lastValidBlockHeight: Long
)

data class TransactionInfo(
    val slot: Long?,
    val blockTime: Long?
)

class SolanaRpcException(message: String) : Exception(message)
