package com.bitchat.android.solana

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.bitchat.android.data.local.TokenGateDao
import com.bitchat.android.data.local.entities.TokenGateConfigEntity
import com.bitchat.android.data.local.entities.TokenGateEligibilityCacheEntity
import com.bitchat.android.data.local.entities.TokenGatePolicyStateEntity
import com.bitchat.android.data.local.entities.TokenGateType
import com.bitchat.android.data.local.entities.TokenGateValidationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates token gate requirements for channels.
 * Checks SPL token balances via Solana RPC and caches results with TTL.
 */
@Singleton
class TokenGateService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletService: SolanaWalletService,
    private val rpcService: SolanaRpcService,
    private val tokenGateDao: TokenGateDao
) {
    companion object {
        private const val TAG = "TokenGateService"
    }

    /**
     * Create a token gate for a channel.
     */
    suspend fun createTokenGate(
        channelKey: String,
        gateType: String,
        tokenMintAddress: String,
        minBalance: Long,
        tokenSymbol: String = "",
        tokenDecimals: Int = 0
    ): Result<TokenGateConfigEntity> = withContext(Dispatchers.IO) {
        try {
            if (gateType == TokenGateType.NFT_COLLECTION && !rpcService.supportsNftCollectionGates()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Configured RPC does not support NFT collection APIs. Use a DAS-capable Solana RPC endpoint."
                    )
                )
            }

            val creatorPubKey = walletService.getPublicKeyBase58()
                ?: return@withContext Result.failure(IllegalStateException("No wallet found. Create a wallet first."))
            val existing = tokenGateDao.getTokenGate(channelKey)
            val policyState = tokenGateDao.getPolicyState(channelKey)
            val knownCreator = existing?.creatorPublicKey ?: policyState?.creatorPublicKey
            if (!knownCreator.isNullOrBlank() && knownCreator != creatorPubKey) {
                return@withContext Result.failure(
                    IllegalStateException("Only the original creator can update this token gate.")
                )
            }
            val currentVersion = maxOf(existing?.policyVersion ?: 0, policyState?.lastPolicyVersion ?: 0)
            val nextPolicyVersion = currentVersion + 1
            val config = TokenGateConfigEntity(
                channelKey = channelKey,
                gateType = gateType,
                tokenMintAddress = tokenMintAddress,
                minBalance = minBalance,
                tokenSymbol = tokenSymbol,
                tokenDecimals = tokenDecimals,
                creatorPublicKey = creatorPubKey,
                createdAt = System.currentTimeMillis(),
                policyVersion = nextPolicyVersion
            )
            val withHash = config.copy(gateHash = computeGateHash(config))
            tokenGateDao.insertTokenGate(withHash)
            tokenGateDao.upsertPolicyState(
                TokenGatePolicyStateEntity(
                    channelKey = channelKey,
                    creatorPublicKey = creatorPubKey,
                    lastPolicyVersion = withHash.policyVersion,
                    lastGateHash = withHash.gateHash,
                    updatedAt = withHash.createdAt,
                    isRemoved = false
                )
            )
            tokenGateDao.deleteEligibilityCacheForChannel(channelKey)
            Log.d(TAG, "Created token gate for $channelKey: $minBalance $tokenSymbol ($gateType)")
            Result.success(withHash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create token gate: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Check if a channel has a token gate.
     */
    suspend fun isTokenGated(channelKey: String): Boolean {
        return tokenGateDao.isTokenGated(channelKey)
    }

    /**
     * Get token gate config for a channel.
     */
    suspend fun getTokenGate(channelKey: String): TokenGateConfigEntity? {
        val config = tokenGateDao.getTokenGate(channelKey) ?: return null
        if (config.gateHash.isNotBlank()) return config
        val hashed = config.copy(gateHash = computeGateHash(config))
        tokenGateDao.insertTokenGate(hashed)
        return hashed
    }

    /**
     * Validate the current user's eligibility for a token-gated channel.
     * Uses cached result if still within TTL, otherwise queries Solana RPC.
     *
     * Returns a pair of (isEligible, displayMessage).
     */
    suspend fun validateEligibility(
        channelKey: String,
        mode: ValidationMode = ValidationMode.PREFER_CACHE_THEN_ONLINE
    ): Result<TokenGateValidationResult> = withContext(Dispatchers.IO) {
        try {
            val configRaw = tokenGateDao.getTokenGate(channelKey)
                ?: return@withContext Result.failure(IllegalStateException("No token gate found for $channelKey"))
            val config = if (configRaw.gateHash.isBlank()) {
                val hashed = configRaw.copy(gateHash = computeGateHash(configRaw))
                tokenGateDao.insertTokenGate(hashed)
                hashed
            } else {
                configRaw
            }

            val userPubKey = walletService.getPublicKeyBase58()
                ?: return@withContext Result.failure(IllegalStateException("No wallet found. Create a wallet first."))

            validateEligibilityForWallet(config, channelKey, userPubKey, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Token gate validation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Validate eligibility for an arbitrary wallet address (used for peer enforcement).
     */
    suspend fun validateWalletEligibility(
        channelKey: String,
        walletAddress: String,
        mode: ValidationMode = ValidationMode.PREFER_CACHE_THEN_ONLINE
    ): Result<TokenGateValidationResult> = withContext(Dispatchers.IO) {
        try {
            val configRaw = tokenGateDao.getTokenGate(channelKey)
                ?: return@withContext Result.failure(IllegalStateException("No token gate found for $channelKey"))
            val config = if (configRaw.gateHash.isBlank()) {
                val hashed = configRaw.copy(gateHash = computeGateHash(configRaw))
                tokenGateDao.insertTokenGate(hashed)
                hashed
            } else {
                configRaw
            }

            val normalizedWallet = walletAddress.trim()
            if (normalizedWallet.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Wallet address required"))
            }

            validateEligibilityForWallet(config, channelKey, normalizedWallet, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Token gate peer validation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun validateEligibilityForWallet(
        config: TokenGateConfigEntity,
        channelKey: String,
        walletAddress: String,
        mode: ValidationMode
    ): Result<TokenGateValidationResult> {
        val now = System.currentTimeMillis()
        val cache = tokenGateDao.getEligibilityCache(channelKey, walletAddress, config.gateHash)
        val hasFreshCache = cache != null && cache.expiresAt > now

        if (hasFreshCache && mode != ValidationMode.STRICT_ONLINE) {
            val cached = cache!!
            return Result.success(
                TokenGateValidationResult(
                    decision = if (cached.isEligible) GateDecision.ALLOW else GateDecision.DENY,
                    reasonCode = if (cached.isEligible) ValidationReason.CACHED_ALLOW else ValidationReason.CACHED_DENY,
                    userBalance = cached.observedBalance,
                    requiredBalance = config.minBalance,
                    tokenSymbol = config.tokenSymbol,
                    tokenDecimals = config.tokenDecimals,
                    fromCache = true,
                    validUntil = cached.expiresAt
                )
            )
        }

        if (mode == ValidationMode.CACHE_ONLY) {
            return Result.success(
                TokenGateValidationResult(
                    decision = GateDecision.UNKNOWN_OFFLINE,
                    reasonCode = ValidationReason.CACHE_MISS,
                    userBalance = -1,
                    requiredBalance = config.minBalance,
                    tokenSymbol = config.tokenSymbol,
                    tokenDecimals = config.tokenDecimals,
                    fromCache = false,
                    validUntil = now
                )
            )
        }

        if (!hasInternetConnectivity()) {
            return Result.success(
                TokenGateValidationResult(
                    decision = GateDecision.UNKNOWN_OFFLINE,
                    reasonCode = ValidationReason.OFFLINE_CACHE_MISS,
                    userBalance = -1,
                    requiredBalance = config.minBalance,
                    tokenSymbol = config.tokenSymbol,
                    tokenDecimals = config.tokenDecimals,
                    fromCache = false,
                    validUntil = now
                )
            )
        }

        val balanceResult = when (config.gateType) {
            TokenGateType.SPL_TOKEN -> {
                rpcService.getTokenBalance(walletAddress, config.tokenMintAddress)
            }
            TokenGateType.NFT_SPECIFIC -> {
                // NFT by specific mint: any balance > 0 of that mint means holder.
                rpcService.getTokenBalance(walletAddress, config.tokenMintAddress)
            }
            TokenGateType.NFT_COLLECTION -> {
                if (!rpcService.supportsNftCollectionGates()) {
                    return Result.failure(
                        IllegalStateException(
                            "Configured RPC does not support NFT collection APIs. Use a DAS-capable Solana RPC endpoint."
                        )
                    )
                }
                rpcService.hasNftFromCollection(walletAddress, config.tokenMintAddress)
                    .map { has -> if (has) 1L else 0L }
            }
            else -> Result.failure(IllegalArgumentException("Unknown gate type: ${config.gateType}"))
        }

        val userBalance = balanceResult.getOrElse { error ->
            Log.e(TAG, "Failed to query token balance: ${error.message}")
            return Result.failure(error)
        }

        val isEligible = userBalance >= config.minBalance

        val expiresAt = now + config.validationTtlMs
        val localWallet = runCatching { walletService.getPublicKeyBase58() }.getOrNull()
        if (!localWallet.isNullOrBlank() && localWallet == walletAddress) {
            tokenGateDao.updateEligibility(channelKey, isEligible, now)
        }
        tokenGateDao.upsertEligibilityCache(
            TokenGateEligibilityCacheEntity(
                channelKey = channelKey,
                walletAddress = walletAddress,
                gateHash = config.gateHash,
                isEligible = isEligible,
                observedBalance = userBalance,
                validatedAt = now,
                expiresAt = expiresAt,
                source = TokenGateValidationSource.RPC
            )
        )

        Log.d(
            TAG,
            "Token gate validation for $channelKey wallet=${walletAddress.take(8)}...: eligible=$isEligible, balance=$userBalance, required=${config.minBalance}"
        )

        return Result.success(
            TokenGateValidationResult(
                decision = if (isEligible) GateDecision.ALLOW else GateDecision.DENY,
                reasonCode = if (isEligible) ValidationReason.RPC_ALLOW else ValidationReason.RPC_DENY,
                userBalance = userBalance,
                requiredBalance = config.minBalance,
                tokenSymbol = config.tokenSymbol,
                tokenDecimals = config.tokenDecimals,
                fromCache = false,
                validUntil = expiresAt
            )
        )
    }

    /**
     * Remove a token gate from a channel.
     */
    suspend fun removeTokenGate(channelKey: String) = withContext(Dispatchers.IO) {
        val existing = tokenGateDao.getTokenGate(channelKey)
        val policyState = tokenGateDao.getPolicyState(channelKey)
        val creator = existing?.creatorPublicKey ?: policyState?.creatorPublicKey ?: return@withContext
        val currentVersion = maxOf(existing?.policyVersion ?: 0, policyState?.lastPolicyVersion ?: 0)
        val nextVersion = currentVersion + 1
        val gateHash = existing?.gateHash ?: policyState?.lastGateHash.orEmpty()
        tokenGateDao.deleteTokenGate(channelKey)
        tokenGateDao.deleteEligibilityCacheForChannel(channelKey)
        tokenGateDao.upsertPolicyState(
            TokenGatePolicyStateEntity(
                channelKey = channelKey,
                creatorPublicKey = creator,
                lastPolicyVersion = nextVersion,
                lastGateHash = gateHash,
                updatedAt = System.currentTimeMillis(),
                isRemoved = true
            )
        )
    }

    /**
     * Apply a token-gate policy received over mesh sync.
     * Returns true if local state changed.
     */
    suspend fun applySyncedPolicy(
        payload: TokenGatePolicyPayload,
        senderSolanaAddress: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val senderCreator = senderSolanaAddress?.trim().orEmpty()
            if (senderCreator.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Unauthenticated token-gate policy sender")
                )
            }

            val payloadCreator = payload.creatorPublicKey?.trim().orEmpty()
            if (payloadCreator.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Missing creatorPublicKey in synced policy")
                )
            }
            if (payloadCreator != senderCreator) {
                return@withContext Result.failure(
                    IllegalStateException("Sender does not match policy creator")
                )
            }

            val existing = tokenGateDao.getTokenGate(payload.channelKey)
            val policyState = tokenGateDao.getPolicyState(payload.channelKey)
            val currentVersion = maxOf(existing?.policyVersion ?: 0, policyState?.lastPolicyVersion ?: 0)
            val knownCreator = existing?.creatorPublicKey ?: policyState?.creatorPublicKey
            if (!knownCreator.isNullOrBlank() && knownCreator != senderCreator) {
                return@withContext Result.success(false)
            }
            val now = System.currentTimeMillis()

            when (payload.action) {
                TokenGatePolicyAction.REMOVE -> {
                    if (payload.policyVersion <= currentVersion) return@withContext Result.success(false)
                    val expectedGateHash = existing?.gateHash ?: policyState?.lastGateHash
                    if (!expectedGateHash.isNullOrBlank() && payload.gateHash != expectedGateHash) {
                        return@withContext Result.success(false)
                    }
                    tokenGateDao.deleteTokenGate(payload.channelKey)
                    tokenGateDao.deleteEligibilityCacheForChannel(payload.channelKey)
                    tokenGateDao.upsertPolicyState(
                        TokenGatePolicyStateEntity(
                            channelKey = payload.channelKey,
                            creatorPublicKey = senderCreator,
                            lastPolicyVersion = payload.policyVersion,
                            lastGateHash = payload.gateHash,
                            updatedAt = now,
                            isRemoved = true
                        )
                    )
                    return@withContext Result.success(true)
                }

                TokenGatePolicyAction.UPSERT -> {
                    val gateType = payload.gateType ?: return@withContext Result.failure(
                        IllegalArgumentException("Missing gateType in synced upsert policy")
                    )
                    val mint = payload.tokenMintAddress ?: return@withContext Result.failure(
                        IllegalArgumentException("Missing tokenMintAddress in synced upsert policy")
                    )
                    val minBalance = payload.minBalance ?: return@withContext Result.failure(
                        IllegalArgumentException("Missing minBalance in synced upsert policy")
                    )
                    val tokenDecimals = payload.tokenDecimals ?: 0
                    val tokenSymbol = payload.tokenSymbol ?: ""

                    if (payload.policyVersion <= 0) return@withContext Result.failure(
                        IllegalArgumentException("Invalid policyVersion in synced policy")
                    )

                    val incoming = TokenGateConfigEntity(
                        channelKey = payload.channelKey,
                        gateType = gateType,
                        tokenMintAddress = mint,
                        minBalance = minBalance,
                        tokenSymbol = tokenSymbol,
                        tokenDecimals = tokenDecimals,
                        creatorPublicKey = senderCreator,
                        createdAt = payload.updatedAt,
                        policyVersion = payload.policyVersion,
                        gateHash = payload.gateHash
                    )

                    val computed = computeGateHash(incoming)
                    if (computed != payload.gateHash) {
                        return@withContext Result.failure(
                            IllegalArgumentException("Invalid gateHash in synced policy")
                        )
                    }

                    if (incoming.policyVersion <= currentVersion) {
                        return@withContext Result.success(false)
                    }

                    tokenGateDao.insertTokenGate(incoming)
                    tokenGateDao.deleteEligibilityCacheForChannel(payload.channelKey)
                    tokenGateDao.upsertPolicyState(
                        TokenGatePolicyStateEntity(
                            channelKey = payload.channelKey,
                            creatorPublicKey = senderCreator,
                            lastPolicyVersion = incoming.policyVersion,
                            lastGateHash = incoming.gateHash,
                            updatedAt = now,
                            isRemoved = false
                        )
                    )
                    return@withContext Result.success(true)
                }

                else -> {
                    return@withContext Result.failure(
                        IllegalArgumentException("Unknown token gate policy action: ${payload.action}")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply synced token gate policy: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Observe all token-gated channel configurations.
     */
    fun observeAllTokenGates(): Flow<List<TokenGateConfigEntity>> {
        return tokenGateDao.observeAllTokenGates()
    }

    /**
     * Get all token-gated channel configurations.
     */
    suspend fun getAllTokenGates(): List<TokenGateConfigEntity> = withContext(Dispatchers.IO) {
        tokenGateDao.getAllTokenGates().map { config ->
            if (config.gateHash.isNotBlank()) {
                config
            } else {
                val hashed = config.copy(gateHash = computeGateHash(config))
                tokenGateDao.insertTokenGate(hashed)
                hashed
            }
        }
    }

    /**
     * Format a token amount for display using the token's decimal places.
     */
    fun formatTokenAmount(amount: Long, decimals: Int): String {
        if (decimals == 0) return amount.toString()
        val divisor = Math.pow(10.0, decimals.toDouble())
        val display = amount.toDouble() / divisor
        return if (display == display.toLong().toDouble()) {
            display.toLong().toString()
        } else {
            "%.${decimals}f".format(display).trimEnd('0').trimEnd('.')
        }
    }

    fun formatRequirementText(result: TokenGateValidationResult): String {
        val symbol = result.tokenSymbol.ifEmpty { "tokens" }
        val required = formatTokenAmount(result.requiredBalance, result.tokenDecimals)
        return if (result.userBalance >= 0) {
            val actual = formatTokenAmount(result.userBalance, result.tokenDecimals)
            "need at least $required $symbol (you have $actual)"
        } else {
            "need at least $required $symbol"
        }
    }

    private fun hasInternetConnectivity(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun computeGateHash(config: TokenGateConfigEntity): String {
        val payload = listOf(
            config.gateType,
            config.tokenMintAddress,
            config.minBalance.toString(),
            config.tokenDecimals.toString(),
            config.tokenSymbol,
            config.policyVersion.toString()
        ).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Validation modes for token gate checks.
 */
enum class ValidationMode {
    STRICT_ONLINE,
    PREFER_CACHE_THEN_ONLINE,
    CACHE_ONLY
}

enum class GateDecision {
    ALLOW,
    DENY,
    UNKNOWN_OFFLINE
}

object ValidationReason {
    const val CACHED_ALLOW = "CACHED_ALLOW"
    const val CACHED_DENY = "CACHED_DENY"
    const val RPC_ALLOW = "RPC_ALLOW"
    const val RPC_DENY = "RPC_DENY"
    const val OFFLINE_CACHE_MISS = "OFFLINE_CACHE_MISS"
    const val CACHE_MISS = "CACHE_MISS"
}

/**
 * Result of a token gate eligibility check with explainable state.
 */
data class TokenGateValidationResult(
    val decision: GateDecision,
    val reasonCode: String,
    val userBalance: Long,
    val requiredBalance: Long,
    val tokenSymbol: String,
    val tokenDecimals: Int,
    val fromCache: Boolean,
    val validUntil: Long
)
