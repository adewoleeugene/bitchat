package com.bitchat.android.solana

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.bitchat.android.data.local.TokenGateDao
import com.bitchat.android.data.local.entities.TokenGateConfigEntity
import com.bitchat.android.data.local.entities.TokenGateEligibilityCacheEntity
import com.bitchat.android.data.local.entities.TokenGateType
import com.bitchat.android.data.local.entities.TokenGateValidationSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.security.MessageDigest

class TokenGateServiceTest {
    private val context: Context = mock()
    private val walletService: SolanaWalletService = mock()
    private val rpcService: SolanaRpcService = mock()
    private val tokenGateDao: TokenGateDao = mock()
    private val connectivityManager: ConnectivityManager = mock()
    private val network: Network = mock()
    private val networkCapabilities: NetworkCapabilities = mock()

    private lateinit var service: TokenGateService

    @Before
    fun setUp() {
        service = TokenGateService(context, walletService, rpcService, tokenGateDao)
        runBlocking {
            whenever(walletService.getPublicKeyBase58()).thenReturn("Wallet111111111111111111111111111111111")
        }
    }

    @Test
    fun validateEligibility_freshCache_returnsCachedAllowWithoutRpc() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val gateHash = "hash_v1"
            val now = System.currentTimeMillis()
            val config = sampleConfig(channelKey = channelKey, gateHash = gateHash)
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(config)
            whenever(
                tokenGateDao.getEligibilityCache(
                    channelKey = channelKey,
                    walletAddress = "Wallet111111111111111111111111111111111",
                    gateHash = gateHash
                )
            ).thenReturn(
                TokenGateEligibilityCacheEntity(
                    channelKey = channelKey,
                    walletAddress = "Wallet111111111111111111111111111111111",
                    gateHash = gateHash,
                    isEligible = true,
                    observedBalance = 250L,
                    validatedAt = now - 1_000L,
                    expiresAt = now + 60_000L,
                    source = TokenGateValidationSource.RPC
                )
            )

            val result = service.validateEligibility(channelKey, ValidationMode.PREFER_CACHE_THEN_ONLINE).getOrThrow()

            assertEquals(GateDecision.ALLOW, result.decision)
            assertEquals(ValidationReason.CACHED_ALLOW, result.reasonCode)
            assertTrue(result.fromCache)
            verify(rpcService, never()).getTokenBalance(any(), any())
        }
    }

    @Test
    fun validateEligibility_cacheOnlyMiss_returnsUnknownOffline() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val gateHash = "hash_v1"
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(sampleConfig(channelKey = channelKey, gateHash = gateHash))
            whenever(tokenGateDao.getEligibilityCache(channelKey, "Wallet111111111111111111111111111111111", gateHash)).thenReturn(null)

            val result = service.validateEligibility(channelKey, ValidationMode.CACHE_ONLY).getOrThrow()

            assertEquals(GateDecision.UNKNOWN_OFFLINE, result.decision)
            assertEquals(ValidationReason.CACHE_MISS, result.reasonCode)
            assertFalse(result.fromCache)
            verify(rpcService, never()).getTokenBalance(any(), any())
        }
    }

    @Test
    fun validateEligibility_strictOnlineWithoutInternet_returnsOfflineCacheMiss() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val gateHash = "hash_v1"
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(sampleConfig(channelKey = channelKey, gateHash = gateHash))
            whenever(tokenGateDao.getEligibilityCache(channelKey, "Wallet111111111111111111111111111111111", gateHash)).thenReturn(null)
            whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
            whenever(connectivityManager.activeNetwork).thenReturn(null)

            val result = service.validateEligibility(channelKey, ValidationMode.STRICT_ONLINE).getOrThrow()

            assertEquals(GateDecision.UNKNOWN_OFFLINE, result.decision)
            assertEquals(ValidationReason.OFFLINE_CACHE_MISS, result.reasonCode)
            verify(rpcService, never()).getTokenBalance(any(), any())
        }
    }

    @Test
    fun validateEligibility_strictOnlineWithInternet_queriesRpcAndCachesDecision() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val gateHash = "hash_v1"
            val config = sampleConfig(channelKey = channelKey, gateHash = gateHash, minBalance = 100L)
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(config)
            whenever(tokenGateDao.getEligibilityCache(channelKey, "Wallet111111111111111111111111111111111", gateHash)).thenReturn(null)
            whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
            whenever(connectivityManager.activeNetwork).thenReturn(network)
            whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
            whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
            whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
            whenever(rpcService.getTokenBalance("Wallet111111111111111111111111111111111", config.tokenMintAddress))
                .thenReturn(Result.success(250L))

            val result = service.validateEligibility(channelKey, ValidationMode.STRICT_ONLINE).getOrThrow()

            assertEquals(GateDecision.ALLOW, result.decision)
            assertEquals(ValidationReason.RPC_ALLOW, result.reasonCode)
            assertFalse(result.fromCache)
            verify(tokenGateDao).updateEligibility(eq(channelKey), eq(true), any())
            verify(tokenGateDao).upsertEligibilityCache(
                argThat { channelKey == this.channelKey && gateHash == this.gateHash && isEligible && observedBalance == 250L }
            )
        }
    }

    @Test
    fun validateWalletEligibility_strictOnline_usesProvidedWalletAddress() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val gateHash = "hash_v1"
            val peerWallet = "PeerWallet11111111111111111111111111111111"
            val config = sampleConfig(channelKey = channelKey, gateHash = gateHash, minBalance = 5L)
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(config)
            whenever(tokenGateDao.getEligibilityCache(channelKey, peerWallet, gateHash)).thenReturn(null)
            whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
            whenever(connectivityManager.activeNetwork).thenReturn(network)
            whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
            whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
            whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
            whenever(rpcService.getTokenBalance(peerWallet, config.tokenMintAddress))
                .thenReturn(Result.success(7L))

            val result = service.validateWalletEligibility(
                channelKey = channelKey,
                walletAddress = peerWallet,
                mode = ValidationMode.STRICT_ONLINE
            ).getOrThrow()

            assertEquals(GateDecision.ALLOW, result.decision)
            verify(rpcService).getTokenBalance(peerWallet, config.tokenMintAddress)
        }
    }

    @Test
    fun validateEligibility_solBalanceGate_usesNativeSolBalance() {
        runBlocking {
            val channelKey = "mesh:#sol-gated"
            val gateHash = "hash_sol_v1"
            val config = TokenGateConfigEntity(
                channelKey = channelKey,
                gateType = TokenGateType.SOL_BALANCE,
                tokenMintAddress = "SOL",
                minBalance = 2_000_000_000L, // 2 SOL
                tokenSymbol = "SOL",
                tokenDecimals = 9,
                creatorPublicKey = "Creator1111111111111111111111111111111111",
                policyVersion = 1,
                gateHash = gateHash
            )
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(config)
            whenever(tokenGateDao.getEligibilityCache(channelKey, "Wallet111111111111111111111111111111111", gateHash)).thenReturn(null)
            whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
            whenever(connectivityManager.activeNetwork).thenReturn(network)
            whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(networkCapabilities)
            whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
            whenever(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
            whenever(rpcService.getBalance("Wallet111111111111111111111111111111111"))
                .thenReturn(Result.success(3_000_000_000L))

            val result = service.validateEligibility(channelKey, ValidationMode.STRICT_ONLINE).getOrThrow()

            assertEquals(GateDecision.ALLOW, result.decision)
            verify(rpcService).getBalance("Wallet111111111111111111111111111111111")
            verify(rpcService, never()).getTokenBalance(any(), any())
        }
    }

    @Test
    fun applySyncedPolicy_upsertWithValidHash_insertsPolicy() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val config = sampleConfig(channelKey = channelKey, gateHash = "")
            val validHash = computeGateHash(config.copy(gateHash = ""))
            val payload = TokenGatePolicyPayload(
                action = TokenGatePolicyAction.UPSERT,
                channelKey = channelKey,
                gateType = config.gateType,
                tokenMintAddress = config.tokenMintAddress,
                minBalance = config.minBalance,
                tokenSymbol = config.tokenSymbol,
                tokenDecimals = config.tokenDecimals,
                creatorPublicKey = config.creatorPublicKey,
                policyVersion = config.policyVersion,
                gateHash = validHash,
                updatedAt = System.currentTimeMillis()
            )

            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(null)

            val applied = service.applySyncedPolicy(
                payload = payload,
                senderSolanaAddress = config.creatorPublicKey
            ).getOrThrow()

            assertTrue(applied)
            verify(tokenGateDao).insertTokenGate(
                argThat { this.channelKey == channelKey && this.gateHash == validHash }
            )
            verify(tokenGateDao).deleteEligibilityCacheForChannel(channelKey)
        }
    }

    @Test
    fun applySyncedPolicy_rejectsWhenSenderDoesNotMatchPayloadCreator() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val config = sampleConfig(channelKey = channelKey, gateHash = "")
            val validHash = computeGateHash(config.copy(gateHash = ""))
            val payload = TokenGatePolicyPayload(
                action = TokenGatePolicyAction.UPSERT,
                channelKey = channelKey,
                gateType = config.gateType,
                tokenMintAddress = config.tokenMintAddress,
                minBalance = config.minBalance,
                tokenSymbol = config.tokenSymbol,
                tokenDecimals = config.tokenDecimals,
                creatorPublicKey = config.creatorPublicKey,
                policyVersion = config.policyVersion,
                gateHash = validHash
            )

            val result = service.applySyncedPolicy(
                payload = payload,
                senderSolanaAddress = "Attacker11111111111111111111111111111111"
            )

            assertTrue(result.isFailure)
            verify(tokenGateDao, never()).insertTokenGate(any())
        }
    }

    @Test
    fun applySyncedPolicy_removeRejectsSenderThatIsNotExistingCreator() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val existing = sampleConfig(channelKey = channelKey, gateHash = "hash_v1")
            val payload = TokenGatePolicyPayload(
                action = TokenGatePolicyAction.REMOVE,
                channelKey = channelKey,
                policyVersion = existing.policyVersion,
                gateHash = existing.gateHash,
                creatorPublicKey = existing.creatorPublicKey
            )
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(existing)

            val result = service.applySyncedPolicy(
                payload = payload,
                senderSolanaAddress = "Attacker11111111111111111111111111111111"
            )

            assertTrue(result.isFailure)
            verify(tokenGateDao, never()).deleteTokenGate(any())
        }
    }

    @Test
    fun applySyncedPolicy_upsertRejectsEqualVersionReplayEvenIfHashDiffers() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val existing = sampleConfig(channelKey = channelKey, gateHash = "existing_hash", minBalance = 10L)
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(existing)

            val candidate = existing.copy(
                minBalance = 20L,
                gateHash = "",
                policyVersion = existing.policyVersion
            )
            val payload = TokenGatePolicyPayload(
                action = TokenGatePolicyAction.UPSERT,
                channelKey = channelKey,
                gateType = candidate.gateType,
                tokenMintAddress = candidate.tokenMintAddress,
                minBalance = candidate.minBalance,
                tokenSymbol = candidate.tokenSymbol,
                tokenDecimals = candidate.tokenDecimals,
                creatorPublicKey = existing.creatorPublicKey,
                policyVersion = existing.policyVersion,
                gateHash = computeGateHash(candidate)
            )

            val result = service.applySyncedPolicy(payload, existing.creatorPublicKey)

            assertTrue(result.isSuccess)
            assertFalse(result.getOrThrow())
            verify(tokenGateDao, never()).insertTokenGate(any())
        }
    }

    @Test
    fun removeTokenGate_writesTombstoneWithIncrementedVersion() {
        runBlocking {
            val channelKey = "mesh:#vip"
            val existing = sampleConfig(channelKey = channelKey, gateHash = "hash_v9", minBalance = 1L)
                .copy(policyVersion = 9)
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(existing)

            service.removeTokenGate(channelKey)

            verify(tokenGateDao).upsertPolicyState(
                argThat {
                    this.channelKey == channelKey &&
                        this.creatorPublicKey == existing.creatorPublicKey &&
                        this.lastPolicyVersion == 10 &&
                        this.lastGateHash == existing.gateHash &&
                        this.isRemoved
                }
            )
        }
    }

    @Test
    fun createTokenGate_rejectsWhenLocalWalletIsNotOriginalCreator() {
        runBlocking {
            val channelKey = "mesh:#vip"
            whenever(tokenGateDao.getTokenGate(channelKey)).thenReturn(null)
            whenever(tokenGateDao.getPolicyState(channelKey)).thenReturn(
                com.bitchat.android.data.local.entities.TokenGatePolicyStateEntity(
                    channelKey = channelKey,
                    creatorPublicKey = "Creator1111111111111111111111111111111111",
                    lastPolicyVersion = 9,
                    lastGateHash = "hash_v9",
                    isRemoved = true
                )
            )

            val result = service.createTokenGate(
                channelKey = channelKey,
                gateType = TokenGateType.SPL_TOKEN,
                tokenMintAddress = "Mint1111111111111111111111111111111111111",
                minBalance = 1L
            )

            assertTrue(result.isFailure)
            verify(tokenGateDao, never()).insertTokenGate(any())
        }
    }

    private fun sampleConfig(
        channelKey: String,
        gateHash: String,
        minBalance: Long = 10L
    ): TokenGateConfigEntity {
        return TokenGateConfigEntity(
            channelKey = channelKey,
            gateType = TokenGateType.SPL_TOKEN,
            tokenMintAddress = "Mint1111111111111111111111111111111111111",
            minBalance = minBalance,
            tokenSymbol = "CHAT",
            tokenDecimals = 0,
            creatorPublicKey = "Creator1111111111111111111111111111111111",
            policyVersion = 1,
            gateHash = gateHash
        )
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
