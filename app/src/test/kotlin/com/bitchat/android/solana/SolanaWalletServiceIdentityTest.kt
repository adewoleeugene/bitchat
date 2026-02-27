package com.bitchat.android.solana

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.data.local.WalletDao
import com.bitchat.android.data.local.entities.WalletEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SolanaWalletServiceIdentityTest {

    private lateinit var context: Context
    private lateinit var walletDao: FakeWalletDao
    private lateinit var rpcService: SolanaRpcService
    private lateinit var walletService: SolanaWalletService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("solana_wallet_secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE).edit().clear().commit()

        walletDao = FakeWalletDao()
        rpcService = mock()
        walletService = SolanaWalletService(context, walletDao, rpcService)
    }

    @Test
    fun identityKeyBootstrapsWalletAndSigns() {
        val identity = ByteArray(32) { (it + 3).toByte() }
        context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
            .edit()
            .putString("ed25519_signing_private_key", Base64.encodeToString(identity, Base64.NO_WRAP))
            .commit()

        val address = walletService.getPublicKeyBase58()
        val privateKey = walletService.getPrivateKeyBase58()

        assertNotNull(address)
        assertNotNull(privateKey)
        assertTrue(walletService.hasWallet())
        assertEquals("Identity-Derived Wallet", walletService.getWalletSourceLabel())

        val active = runBlocking { walletDao.getActiveWallet() }
        assertNotNull(active)
        assertEquals("Identity-Derived Wallet", active!!.label)
    }

    @Test
    fun reportsIssueWhenIdentityKeyMissing() {
        val issue = walletService.getInitializationIssueMessage()
        assertNotNull(issue)
        assertTrue(issue!!.contains("identity key", ignoreCase = true))
    }

    @Test
    fun exportCooldownAndAuditAreEnforced() {
        val identity = ByteArray(32) { (it + 9).toByte() }
        context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
            .edit()
            .putString("ed25519_signing_private_key", Base64.encodeToString(identity, Base64.NO_WRAP))
            .commit()
        walletService.getPublicKeyBase58()

        val firstGate = walletService.canRevealPrivateKeyForExport(nowMs = 1_000L)
        assertTrue(firstGate.isSuccess)
        walletService.markPrivateKeyExportRevealed(nowMs = 1_000L)

        val secondGate = walletService.canRevealPrivateKeyForExport(nowMs = 10_000L)
        assertTrue(secondGate.isFailure)

        val thirdGate = walletService.canRevealPrivateKeyForExport(nowMs = 40_500L)
        assertTrue(thirdGate.isSuccess)

        val summary = walletService.getPrivateKeyExportAuditSummary()
        assertTrue(summary.contains("used 1 time", ignoreCase = true) || summary.contains("used 1 time(s)", ignoreCase = true))
    }

    @Test
    fun lifecycleWarningShownWhenIdentityKeyChangesForDerivedWallet() {
        val firstIdentity = ByteArray(32) { (it + 1).toByte() }
        context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
            .edit()
            .putString("ed25519_signing_private_key", Base64.encodeToString(firstIdentity, Base64.NO_WRAP))
            .commit()
        walletService.getPublicKeyBase58()

        val secondIdentity = ByteArray(32) { (it + 2).toByte() }
        context.getSharedPreferences("bitchat_crypto", Context.MODE_PRIVATE)
            .edit()
            .putString("ed25519_signing_private_key", Base64.encodeToString(secondIdentity, Base64.NO_WRAP))
            .commit()

        val warning = walletService.getLifecycleWarningMessage()
        assertNotNull(warning)
        assertTrue(warning!!.contains("Identity key changed", ignoreCase = true))
    }
}

private class FakeWalletDao : WalletDao {
    private val state = MutableStateFlow<WalletEntity?>(null)
    private val all = MutableStateFlow<List<WalletEntity>>(emptyList())

    override suspend fun insertWallet(wallet: WalletEntity) {
        val existing = all.value.toMutableList().filterNot { it.publicKey == wallet.publicKey }.toMutableList()
        existing.add(wallet)
        all.value = existing.sortedByDescending { it.createdAt }
        if (wallet.isActive) state.value = wallet
    }

    override suspend fun updateWallet(wallet: WalletEntity) {
        insertWallet(wallet)
    }

    override suspend fun getActiveWallet(): WalletEntity? = state.value

    override fun observeActiveWallet(): Flow<WalletEntity?> = state

    override fun observeAllWallets(): Flow<List<WalletEntity>> = all

    override suspend fun deactivateAll() {
        state.value = null
        all.value = all.value.map { it.copy(isActive = false) }
    }

    override suspend fun updateBalance(publicKey: String, lamports: Long, updatedAt: Long) {
        val current = all.value.toMutableList()
        val idx = current.indexOfFirst { it.publicKey == publicKey }
        if (idx >= 0) {
            val updated = current[idx].copy(lastBalanceLamports = lamports, lastBalanceUpdatedAt = updatedAt)
            current[idx] = updated
            all.value = current.sortedByDescending { it.createdAt }
            if (updated.isActive) state.value = updated
        }
    }

    override suspend fun deleteWallet(publicKey: String) {
        all.value = all.value.filterNot { it.publicKey == publicKey }
        if (state.value?.publicKey == publicKey) state.value = null
    }
}
