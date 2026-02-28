package com.bitchat.android.solana

import android.content.Context
import android.util.Log
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import com.bitchat.android.data.local.WalletDao
import com.bitchat.android.data.local.entities.WalletEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Solana wallet service handling BIP39 mnemonic generation,
 * Ed25519 keypair derivation, and secure storage via EncryptedSharedPreferences.
 *
 * Mirrors the NostrIdentityBridge pattern for secure key management.
 */
@Singleton
class SolanaWalletService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletDao: WalletDao,
    private val rpcService: SolanaRpcService
) {
    companion object {
        private const val TAG = "SolanaWalletService"
        private const val PREFS_NAME = "solana_wallet_secure"
        private const val KEY_MNEMONIC = "wallet_mnemonic"
        private const val KEY_PRIVATE_KEY = "wallet_private_key"
        private const val KEY_PUBLIC_KEY = "wallet_public_key"
        private const val IDENTITY_PREFS_NAME = "bitchat_crypto"
        private const val IDENTITY_ED25519_PRIVATE_KEY_PREF = "ed25519_signing_private_key"
        private const val LAMPORTS_PER_SOL = 1_000_000_000L
    }

    private val ed25519Spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    // Cached keypair for signing
    private var cachedPrivateKey: EdDSAPrivateKey? = null
    private var cachedPublicKey: EdDSAPublicKey? = null

    private val securePrefs by lazy {
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(
                context, androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS
            ).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()

            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to standard: ${e.message}")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Check if a wallet already exists.
     */
    fun hasWallet(): Boolean {
        ensureWalletInitializedFromIdentity()
        return securePrefs.contains(KEY_PRIVATE_KEY)
    }

    /**
     * Create a new wallet with a fresh BIP39 mnemonic.
     * Returns the mnemonic phrase for the user to back up.
     */
    suspend fun createWallet(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Generate 24-word mnemonic (256 bits of entropy)
            val mnemonicCode = Mnemonics.MnemonicCode(Mnemonics.WordCount.COUNT_24)
            val mnemonicPhrase = String(mnemonicCode.chars)

            // Derive seed from mnemonic (BIP39 standard)
            val seed = mnemonicCode.toSeed()

            // Derive Ed25519 keypair from seed (first 32 bytes as per Solana convention)
            val keyBytes = seed.copyOfRange(0, 32)
            val keypair = SolanaKeyDerivation.deriveKeypair(keyBytes)
            val publicKeyBase58 = SolanaKeyDerivation.encodeBase58(keypair.second)

            // Store securely
            securePrefs.edit()
                .putString(KEY_MNEMONIC, mnemonicPhrase)
                .putString(KEY_PRIVATE_KEY, android.util.Base64.encodeToString(keypair.first, android.util.Base64.NO_WRAP))
                .putString(KEY_PUBLIC_KEY, android.util.Base64.encodeToString(keypair.second, android.util.Base64.NO_WRAP))
                .apply()

            // Cache keys
            cacheKeypair(keypair.first)

            // Persist wallet metadata to Room
            walletDao.deactivateAll()
            walletDao.insertWallet(
                WalletEntity(
                    publicKey = publicKeyBase58,
                    createdAt = System.currentTimeMillis(),
                    isActive = true,
                    label = "Mnemonic Wallet"
                )
            )

            Log.d(TAG, "Created new mnemonic wallet")
            Result.success(mnemonicPhrase)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create wallet: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Restore wallet from an existing BIP39 mnemonic phrase.
     */
    suspend fun restoreWallet(mnemonicPhrase: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Validate mnemonic
            val mnemonicCode = Mnemonics.MnemonicCode(mnemonicPhrase)
            mnemonicCode.validate()

            // Derive seed and keypair
            val seed = mnemonicCode.toSeed()
            val keyBytes = seed.copyOfRange(0, 32)
            val keypair = SolanaKeyDerivation.deriveKeypair(keyBytes)
            val publicKeyBase58 = SolanaKeyDerivation.encodeBase58(keypair.second)

            // Store securely
            securePrefs.edit()
                .putString(KEY_MNEMONIC, mnemonicPhrase)
                .putString(KEY_PRIVATE_KEY, android.util.Base64.encodeToString(keypair.first, android.util.Base64.NO_WRAP))
                .putString(KEY_PUBLIC_KEY, android.util.Base64.encodeToString(keypair.second, android.util.Base64.NO_WRAP))
                .apply()

            // Cache keys
            cacheKeypair(keypair.first)

            // Persist wallet metadata to Room
            walletDao.deactivateAll()
            walletDao.insertWallet(
                WalletEntity(
                    publicKey = publicKeyBase58,
                    createdAt = System.currentTimeMillis(),
                    isActive = true,
                    label = "Imported Mnemonic Wallet"
                )
            )

            Log.d(TAG, "Restored mnemonic wallet")
            Result.success(publicKeyBase58)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore wallet: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Restore wallet from a raw 32-byte Ed25519 private key encoded as Base58.
     */
    suspend fun restoreWalletFromPrivateKeyBase58(privateKeyBase58: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val privateKeyBytes = decodeBase58(privateKeyBase58.trim())
            if (privateKeyBytes.size != 32) {
                return@withContext Result.failure(IllegalArgumentException("Private key must decode to 32 bytes"))
            }

            val keypair = SolanaKeyDerivation.deriveKeypair(privateKeyBytes)
            val publicKeyBase58 = SolanaKeyDerivation.encodeBase58(keypair.second)

            securePrefs.edit()
                .remove(KEY_MNEMONIC)
                .putString(KEY_PRIVATE_KEY, android.util.Base64.encodeToString(keypair.first, android.util.Base64.NO_WRAP))
                .putString(KEY_PUBLIC_KEY, android.util.Base64.encodeToString(keypair.second, android.util.Base64.NO_WRAP))
                .apply()

            cacheKeypair(keypair.first)

            walletDao.deactivateAll()
            walletDao.insertWallet(
                WalletEntity(
                    publicKey = publicKeyBase58,
                    createdAt = System.currentTimeMillis(),
                    isActive = true,
                    label = "Imported Private Key Wallet"
                )
            )

            Log.d(TAG, "Restored wallet from raw private key")
            Result.success(publicKeyBase58)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore private key wallet: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Get the active wallet's public key (Base58 encoded).
     */
    fun getPublicKeyBase58(): String? {
        ensureWalletInitializedFromIdentity()
        val pubKeyBase64 = securePrefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        val pubKeyBytes = android.util.Base64.decode(pubKeyBase64, android.util.Base64.NO_WRAP)
        ensureWalletMetadata(pubKeyBytes)
        return SolanaKeyDerivation.encodeBase58(pubKeyBytes)
    }

    /**
     * Get the raw 32-byte public key.
     */
    fun getPublicKeyBytes(): ByteArray? {
        ensureWalletInitializedFromIdentity()
        val pubKeyBase64 = securePrefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        val pubKeyBytes = android.util.Base64.decode(pubKeyBase64, android.util.Base64.NO_WRAP)
        ensureWalletMetadata(pubKeyBytes)
        return pubKeyBytes
    }

    /**
     * Fetch balance from the Solana devnet and update Room cache.
     */
    suspend fun refreshBalance(): Result<Long> {
        val publicKey = getPublicKeyBase58() ?: return Result.failure(
            IllegalStateException("No wallet found")
        )
        val balanceResult = rpcService.getBalance(publicKey)
        balanceResult.onSuccess { lamports ->
            walletDao.updateBalance(publicKey, lamports, System.currentTimeMillis())
        }
        return balanceResult
    }

    /**
     * Update cached balance from a mesh-relayed response.
     * This updates local cache only; it does not prove canonical on-chain state.
     */
    suspend fun updateCachedBalanceFromMesh(lamports: Long, updatedAtMs: Long = System.currentTimeMillis()): Result<Unit> {
        val publicKey = getPublicKeyBase58() ?: return Result.failure(
            IllegalStateException("No wallet found")
        )
        return try {
            walletDao.updateBalance(publicKey, lamports, updatedAtMs)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get cached balance from Room (lamports).
     */
    suspend fun getCachedBalanceLamports(): Long {
        val wallet = walletDao.getActiveWallet() ?: return 0L
        return wallet.lastBalanceLamports
    }

    /**
     * Observe the active wallet entity reactively.
     */
    fun observeActiveWallet(): Flow<WalletEntity?> {
        return walletDao.observeActiveWallet()
    }

    /**
     * Convert lamports to SOL display string.
     */
    fun lamportsToSol(lamports: Long): String {
        val sol = lamports.toDouble() / LAMPORTS_PER_SOL
        return "%.9f".format(sol).trimEnd('0').trimEnd('.')
    }

    /**
     * Get shortened display address.
     */
    fun getShortAddress(): String? {
        val address = getPublicKeyBase58() ?: return null
        return if (address.length > 12) {
            "${address.take(6)}...${address.takeLast(4)}"
        } else {
            address
        }
    }

    /**
     * Get the stored mnemonic phrase for backup/export.
     */
    fun getMnemonic(): String? {
        return securePrefs.getString(KEY_MNEMONIC, null)
    }

    /**
     * Get the raw private key as Base58 for explicit private-key backup.
     */
    fun getPrivateKeyBase58(): String? {
        ensureWalletInitializedFromIdentity()
        val privKeyBase64 = securePrefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val privKeyBytes = android.util.Base64.decode(privKeyBase64, android.util.Base64.NO_WRAP)
        return SolanaKeyDerivation.encodeBase58(privKeyBytes)
    }

    /**
     * Sign arbitrary data with the wallet's Ed25519 private key.
     */
    fun sign(data: ByteArray): ByteArray? {
        ensureWalletInitializedFromIdentity()
        val privKey = getOrLoadPrivateKey() ?: return null
        return try {
            // Ensure EdDSA provider is registered
            if (java.security.Security.getProvider("EdDSA") == null) {
                java.security.Security.addProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider())
            }
            val sig = java.security.Signature.getInstance("NONEwithEdDSA", "EdDSA")
            sig.initSign(privKey)
            sig.update(data)
            sig.sign()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign: ${e.message}", e)
            null
        }
    }

    /**
     * Delete wallet data (mnemonic, keys, Room entry).
     */
    suspend fun deleteWallet() = withContext(Dispatchers.IO) {
        val publicKey = getPublicKeyBase58()
        securePrefs.edit().clear().apply()
        cachedPrivateKey = null
        cachedPublicKey = null
        if (publicKey != null) {
            walletDao.deleteWallet(publicKey)
        }
        Log.d(TAG, "Wallet deleted")
    }

    // --- Internal helpers ---

    private fun cacheKeypair(privateKeyBytes: ByteArray) {
        val privKeySpec = EdDSAPrivateKeySpec(privateKeyBytes, ed25519Spec)
        cachedPrivateKey = EdDSAPrivateKey(privKeySpec)
        val pubKeySpec = EdDSAPublicKeySpec(cachedPrivateKey!!.a, ed25519Spec)
        cachedPublicKey = EdDSAPublicKey(pubKeySpec)
    }

    private fun getOrLoadPrivateKey(): EdDSAPrivateKey? {
        ensureWalletInitializedFromIdentity()
        cachedPrivateKey?.let { return it }

        val privKeyBase64 = securePrefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val privKeyBytes = android.util.Base64.decode(privKeyBase64, android.util.Base64.NO_WRAP)
        cacheKeypair(privKeyBytes)
        return cachedPrivateKey
    }

    /**
     * Auto-derive a deterministic Solana wallet from the existing app Ed25519 identity
     * when no legacy wallet exists. This keeps legacy mnemonic wallets untouched.
     */
    private fun ensureWalletInitializedFromIdentity() {
        if (securePrefs.contains(KEY_PRIVATE_KEY) && securePrefs.contains(KEY_PUBLIC_KEY)) return

        try {
            val identityPrivB64 = context
                .getSharedPreferences(IDENTITY_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(IDENTITY_ED25519_PRIVATE_KEY_PREF, null)
                ?: return

            val identityPrivateKey = android.util.Base64.decode(identityPrivB64, android.util.Base64.DEFAULT)
            if (identityPrivateKey.size != 32) return

            val derivedSeed = SolanaKeyDerivation.derivePrivateKeyFromIdentity(identityPrivateKey)
            val keypair = SolanaKeyDerivation.deriveKeypair(derivedSeed)

            securePrefs.edit()
                .putString(KEY_PRIVATE_KEY, android.util.Base64.encodeToString(keypair.first, android.util.Base64.NO_WRAP))
                .putString(KEY_PUBLIC_KEY, android.util.Base64.encodeToString(keypair.second, android.util.Base64.NO_WRAP))
                .apply()

            cacheKeypair(keypair.first)
            ensureWalletMetadata(keypair.second, "Identity-Derived Wallet")
            Log.d(TAG, "Initialized identity-derived wallet")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize identity-derived wallet: ${e.message}")
        }
    }

    private fun ensureWalletMetadata(publicKeyBytes: ByteArray, defaultLabel: String = "Wallet") {
        try {
            val publicKeyBase58 = SolanaKeyDerivation.encodeBase58(publicKeyBytes)
            runBlocking(Dispatchers.IO) {
                val current = walletDao.getActiveWallet()
                if (current == null || current.publicKey != publicKeyBase58) {
                    walletDao.deactivateAll()
                    walletDao.insertWallet(
                        WalletEntity(
                            publicKey = publicKeyBase58,
                            createdAt = System.currentTimeMillis(),
                            isActive = true,
                            label = defaultLabel
                        )
                    )
                }
            }
        } catch (_: Exception) { }
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

}
