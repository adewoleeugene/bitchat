package com.bitchat.android.solana

import android.util.Log
import com.bitchat.android.data.local.TokenGateDao
import com.bitchat.android.data.local.entities.TokenGateConfigEntity
import com.bitchat.android.data.local.entities.TokenGateType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates token gate requirements for channels.
 * Checks SPL token balances via Solana RPC and caches results with TTL.
 */
@Singleton
class TokenGateService @Inject constructor(
    private val walletService: SolanaWalletService,
    private val rpcService: SolanaRpcService,
    private val tokenGateDao: TokenGateDao
) {
    companion object {
        private const val TAG = "TokenGateService"
        private const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
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
            val creatorPubKey = walletService.getPublicKeyBase58() ?: ""
            val config = TokenGateConfigEntity(
                channelKey = channelKey,
                gateType = gateType,
                tokenMintAddress = tokenMintAddress,
                minBalance = minBalance,
                tokenSymbol = tokenSymbol,
                tokenDecimals = tokenDecimals,
                creatorPublicKey = creatorPubKey,
                createdAt = System.currentTimeMillis()
            )
            tokenGateDao.insertTokenGate(config)
            Log.d(TAG, "Created token gate for $channelKey: $minBalance $tokenSymbol ($gateType)")
            Result.success(config)
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
        return tokenGateDao.getTokenGate(channelKey)
    }

    /**
     * Validate the current user's eligibility for a token-gated channel.
     * Uses cached result if still within TTL, otherwise queries Solana RPC.
     *
     * Returns a pair of (isEligible, displayMessage).
     */
    suspend fun validateEligibility(channelKey: String): Result<TokenGateValidation> = withContext(Dispatchers.IO) {
        try {
            val config = tokenGateDao.getTokenGate(channelKey)
                ?: return@withContext Result.failure(IllegalStateException("No token gate found for $channelKey"))

            val userPubKey = walletService.getPublicKeyBase58()
                ?: return@withContext Result.failure(IllegalStateException("No wallet found. Create a wallet first."))

            // Check if cached validation is still valid
            val now = System.currentTimeMillis()
            if (config.lastValidatedAt > 0 && (now - config.lastValidatedAt) < config.validationTtlMs) {
                val expiresAt = config.lastValidatedAt + config.validationTtlMs
                return@withContext Result.success(
                    TokenGateValidation(
                        isEligible = config.isUserEligible,
                        userBalance = -1, // Unknown from cache
                        requiredBalance = config.minBalance,
                        tokenSymbol = config.tokenSymbol,
                        tokenDecimals = config.tokenDecimals,
                        fromCache = true,
                        validUntil = expiresAt
                    )
                )
            }

            // Query Solana for user's token balance
            val balanceResult = when (config.gateType) {
                TokenGateType.SPL_TOKEN -> {
                    rpcService.getTokenBalance(userPubKey, config.tokenMintAddress)
                }
                TokenGateType.NFT_COLLECTION, TokenGateType.NFT_SPECIFIC -> {
                    // For NFTs, any balance > 0 means they hold the NFT
                    rpcService.getTokenBalance(userPubKey, config.tokenMintAddress)
                }
                else -> Result.failure(IllegalArgumentException("Unknown gate type: ${config.gateType}"))
            }

            val userBalance = balanceResult.getOrElse { error ->
                Log.e(TAG, "Failed to query token balance: ${error.message}")
                return@withContext Result.failure(error)
            }

            val isEligible = userBalance >= config.minBalance

            // Cache the result
            tokenGateDao.updateEligibility(channelKey, isEligible)

            val validUntil = System.currentTimeMillis() + config.validationTtlMs
            Log.d(TAG, "Token gate validation for $channelKey: eligible=$isEligible, balance=$userBalance, required=${config.minBalance}")

            Result.success(
                TokenGateValidation(
                    isEligible = isEligible,
                    userBalance = userBalance,
                    requiredBalance = config.minBalance,
                    tokenSymbol = config.tokenSymbol,
                    tokenDecimals = config.tokenDecimals,
                    fromCache = false,
                    validUntil = validUntil
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Token gate validation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Remove a token gate from a channel.
     */
    suspend fun removeTokenGate(channelKey: String) {
        tokenGateDao.deleteTokenGate(channelKey)
    }

    /**
     * Observe all token-gated channel configurations.
     */
    fun observeAllTokenGates(): Flow<List<TokenGateConfigEntity>> {
        return tokenGateDao.observeAllTokenGates()
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
}

/**
 * Result of a token gate eligibility check.
 */
data class TokenGateValidation(
    val isEligible: Boolean,
    val userBalance: Long,
    val requiredBalance: Long,
    val tokenSymbol: String,
    val tokenDecimals: Int,
    val fromCache: Boolean,
    val validUntil: Long
)
