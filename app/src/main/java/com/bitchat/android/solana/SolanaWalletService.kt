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
import kotlinx.coroutines.flow.map
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
        private const val AUDIT_PREFS_NAME = "solana_wallet_audit"
        private const val KEY_MNEMONIC = "wallet_mnemonic"
        private const val KEY_PRIVATE_KEY = "wallet_private_key"
        private const val KEY_PUBLIC_KEY = "wallet_public_key"
        private const val KEY_WALLET_ORIGIN = "wallet_origin"
        private const val KEY_IDENTITY_FINGERPRINT = "wallet_identity_fingerprint"
        private const val STORE_PREFIX_PRIVATE_KEY = "wallet_store_priv_"
        private const val STORE_PREFIX_PUBLIC_KEY = "wallet_store_pub_"
        private const val STORE_PREFIX_MNEMONIC = "wallet_store_mnemonic_"
        private const val STORE_PREFIX_ORIGIN = "wallet_store_origin_"
        private const val STORE_PREFIX_IDENTITY_FINGERPRINT = "wallet_store_identity_fp_"
        private const val KEY_EXPORT_LAST_AT = "wallet_export_last_at"
        private const val KEY_EXPORT_COUNT = "wallet_export_count"
        private const val KEY_EXPORT_RECENT = "wallet_export_recent"
        private const val IDENTITY_PREFS_NAME = "bitchat_crypto"
        private const val IDENTITY_ED25519_PRIVATE_KEY_PREF = "ed25519_signing_private_key"
        private const val LAMPORTS_PER_SOL = 1_000_000_000L
        private const val EXPORT_COOLDOWN_MS = 30_000L
        private const val MAX_AUDIT_RECENT_ENTRIES = 5
        private const val ORIGIN_IDENTITY_DERIVED = "identity_derived"
        private const val ORIGIN_MNEMONIC = "mnemonic"
        private const val ORIGIN_IMPORTED_MNEMONIC = "imported_mnemonic"
        private const val ORIGIN_IMPORTED_PRIVATE_KEY = "imported_private_key"
    }

    private data class StoredWalletMaterial(
        val publicKeyBase58: String,
        val privateKeyBase64: String,
        val publicKeyBase64: String,
        val mnemonic: String?,
        val origin: String?,
        val identityFingerprint: String?
    )

    private val ed25519Spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    // Cached keypair for signing
    private var cachedPrivateKey: EdDSAPrivateKey? = null
    private var cachedPublicKey: EdDSAPublicKey? = null
    private var secureStorageDegraded: Boolean = false
    private var secureStorageErrorMessage: String? = null
    private val allowInsecureStorageFallback: Boolean by lazy {
        isRobolectricRuntime() || isHostJvmRuntime()
    }

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
            secureStorageDegraded = true
            secureStorageErrorMessage = "Secure wallet storage unavailable on this device."
            if (allowInsecureStorageFallback) {
                Log.w(TAG, "Secure prefs unavailable in debug/test mode; using standard prefs fallback: ${e.message}")
            } else {
                Log.e(TAG, "Secure prefs unavailable in production; wallet operations blocked: ${e.message}")
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private val auditPrefs by lazy {
        context.getSharedPreferences(AUDIT_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Check if a wallet already exists.
     */
    fun hasWallet(): Boolean {
        if (!isWalletStorageUsable()) return false
        ensureWalletInitializedFromIdentity()
        ensureLegacyActiveBackedUp()
        return securePrefs.contains(KEY_PRIVATE_KEY)
    }

    /**
     * Explain why wallet bootstrap is unavailable when no wallet exists.
     * Returns null when wallet material is available.
     */
    fun getInitializationIssueMessage(): String? {
        if (!isWalletStorageUsable()) return secureStorageUnavailableMessage()
        ensureWalletInitializedFromIdentity()
        ensureLegacyActiveBackedUp()
        if (securePrefs.contains(KEY_PRIVATE_KEY) && securePrefs.contains(KEY_PUBLIC_KEY)) return null

        val identityPrivB64 = context
            .getSharedPreferences(IDENTITY_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(IDENTITY_ED25519_PRIVATE_KEY_PREF, null)
            ?: return "Wallet identity key not found yet. Reopen chat and try again."

        val decoded = try {
            android.util.Base64.decode(identityPrivB64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            return "Stored identity key is invalid. Recreate identity or import a wallet key."
        }

        if (decoded.size != 32) {
            return "Stored identity key has invalid length. Recreate identity or import a wallet key."
        }

        return "Wallet could not be initialized from identity. Try importing a private key."
    }

    fun getWalletSourceLabel(): String? {
        if (!isWalletStorageUsable()) return "Secure Storage Unavailable"
        val origin = securePrefs.getString(KEY_WALLET_ORIGIN, null)
        return when (origin) {
            ORIGIN_IDENTITY_DERIVED -> "Identity-Derived Wallet"
            ORIGIN_MNEMONIC -> "Mnemonic Wallet"
            ORIGIN_IMPORTED_MNEMONIC -> "Imported Mnemonic Wallet"
            ORIGIN_IMPORTED_PRIVATE_KEY -> "Imported Private Key Wallet"
            else -> runBlocking(Dispatchers.IO) { walletDao.getActiveWallet()?.label }
        }
    }

    fun getLifecycleWarningMessage(): String? {
        if (!isWalletStorageUsable()) return secureStorageUnavailableMessage()
        val origin = securePrefs.getString(KEY_WALLET_ORIGIN, null)
        if (origin != ORIGIN_IDENTITY_DERIVED) return null
        val storedFingerprint = securePrefs.getString(KEY_IDENTITY_FINGERPRINT, null) ?: return null
        val currentFingerprint = currentIdentityFingerprint()
        return if (currentFingerprint != null && currentFingerprint != storedFingerprint) {
            "Identity key changed since wallet derivation. Verify backup and address continuity."
        } else {
            null
        }
    }

    fun canRevealPrivateKeyForExport(nowMs: Long = System.currentTimeMillis()): Result<Unit> {
        val lastAt = auditPrefs.getLong(KEY_EXPORT_LAST_AT, 0L)
        val elapsed = nowMs - lastAt
        if (lastAt > 0L && elapsed in 0 until EXPORT_COOLDOWN_MS) {
            val remaining = ((EXPORT_COOLDOWN_MS - elapsed) / 1000L).coerceAtLeast(1L)
            return Result.failure(IllegalStateException("Please wait ${remaining}s before exporting again."))
        }
        return Result.success(Unit)
    }

    fun markPrivateKeyExportRevealed(nowMs: Long = System.currentTimeMillis()) {
        val currentCount = auditPrefs.getInt(KEY_EXPORT_COUNT, 0)
        val recentRaw = auditPrefs.getString(KEY_EXPORT_RECENT, "").orEmpty()
        val updatedRecent = (recentRaw.split(",").filter { it.isNotBlank() } + nowMs.toString())
            .takeLast(MAX_AUDIT_RECENT_ENTRIES)
            .joinToString(",")
        auditPrefs.edit()
            .putLong(KEY_EXPORT_LAST_AT, nowMs)
            .putInt(KEY_EXPORT_COUNT, currentCount + 1)
            .putString(KEY_EXPORT_RECENT, updatedRecent)
            .apply()
    }

    fun getPrivateKeyExportAuditSummary(): String {
        val count = auditPrefs.getInt(KEY_EXPORT_COUNT, 0)
        val lastAt = auditPrefs.getLong(KEY_EXPORT_LAST_AT, 0L)
        if (count == 0 || lastAt <= 0L) return "Private key export has not been used."
        val minutesAgo = ((System.currentTimeMillis() - lastAt) / 60_000L).coerceAtLeast(0L)
        return "Private key export used $count time(s), last ${minutesAgo}m ago."
    }

    /**
     * Create a new wallet with a fresh BIP39 mnemonic.
     * Returns the mnemonic phrase for the user to back up.
     */
    suspend fun createWallet(): Result<String> = withContext(Dispatchers.IO) {
        if (!isWalletStorageUsable()) {
            return@withContext Result.failure(IllegalStateException(secureStorageUnavailableMessage()))
        }
        try {
            backupCurrentWalletMaterial()
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
                .putString(KEY_WALLET_ORIGIN, ORIGIN_MNEMONIC)
                .remove(KEY_IDENTITY_FINGERPRINT)
                .apply()
            backupCurrentWalletMaterial()

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
        if (!isWalletStorageUsable()) {
            return@withContext Result.failure(IllegalStateException(secureStorageUnavailableMessage()))
        }
        try {
            backupCurrentWalletMaterial()
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
                .putString(KEY_WALLET_ORIGIN, ORIGIN_IMPORTED_MNEMONIC)
                .remove(KEY_IDENTITY_FINGERPRINT)
                .apply()
            backupCurrentWalletMaterial()

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
        if (!isWalletStorageUsable()) {
            return@withContext Result.failure(IllegalStateException(secureStorageUnavailableMessage()))
        }
        try {
            backupCurrentWalletMaterial()
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
                .putString(KEY_WALLET_ORIGIN, ORIGIN_IMPORTED_PRIVATE_KEY)
                .remove(KEY_IDENTITY_FINGERPRINT)
                .apply()
            backupCurrentWalletMaterial()

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
        if (!isWalletStorageUsable()) return null
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
        if (!isWalletStorageUsable()) return null
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
        if (!isWalletStorageUsable()) {
            return Result.failure(IllegalStateException(secureStorageUnavailableMessage()))
        }
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

    fun observeAllWallets(): Flow<List<WalletEntity>> {
        return walletDao.observeAllWallets().map { wallets ->
            wallets.filter { wallet ->
                hasStoredWalletMaterial(wallet.publicKey)
            }
        }
    }

    suspend fun setActiveWallet(publicKeyBase58: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isWalletStorageUsable()) {
            return@withContext Result.failure(IllegalStateException(secureStorageUnavailableMessage()))
        }
        try {
            val material = getStoredWalletMaterial(publicKeyBase58)
                ?: return@withContext Result.failure(
                    IllegalStateException("Wallet key material unavailable for selected wallet")
                )

            applyAsActiveWallet(material)
            val publicKeyBytes = android.util.Base64.decode(material.publicKeyBase64, android.util.Base64.NO_WRAP)
            ensureWalletMetadata(publicKeyBytes, defaultLabel = "Wallet")
            walletDao.setActiveWallet(publicKeyBase58)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
        if (!isWalletStorageUsable()) return null
        return securePrefs.getString(KEY_MNEMONIC, null)
    }

    /**
     * Get the raw private key as Base58 for explicit private-key backup.
     */
    fun getPrivateKeyBase58(): String? {
        if (!isWalletStorageUsable()) return null
        ensureWalletInitializedFromIdentity()
        val privKeyBase64 = securePrefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val privKeyBytes = android.util.Base64.decode(privKeyBase64, android.util.Base64.NO_WRAP)
        return SolanaKeyDerivation.encodeBase58(privKeyBytes)
    }

    /**
     * Sign arbitrary data with the wallet's Ed25519 private key.
     */
    fun sign(data: ByteArray): ByteArray? {
        if (!isWalletStorageUsable()) return null
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
        if (!isWalletStorageUsable()) return@withContext
        val activePublicKey = getPublicKeyBase58() ?: return@withContext
        deleteStoredWalletMaterial(activePublicKey)
        walletDao.deleteWallet(activePublicKey)

        val nextWallet = walletDao.getAllWallets().firstOrNull()
        if (nextWallet != null) {
            setActiveWallet(nextWallet.publicKey)
            Log.d(TAG, "Deleted wallet $activePublicKey and switched to ${nextWallet.publicKey}")
        } else {
            securePrefs.edit().clear().apply()
            auditPrefs.edit().clear().apply()
            cachedPrivateKey = null
            cachedPublicKey = null
            Log.d(TAG, "Deleted final wallet and cleared secure wallet state")
        }
    }

    // --- Internal helpers ---

    private fun cacheKeypair(privateKeyBytes: ByteArray) {
        val privKeySpec = EdDSAPrivateKeySpec(privateKeyBytes, ed25519Spec)
        cachedPrivateKey = EdDSAPrivateKey(privKeySpec)
        val pubKeySpec = EdDSAPublicKeySpec(cachedPrivateKey!!.a, ed25519Spec)
        cachedPublicKey = EdDSAPublicKey(pubKeySpec)
    }

    private fun getOrLoadPrivateKey(): EdDSAPrivateKey? {
        if (!isWalletStorageUsable()) return null
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
        if (!isWalletStorageUsable()) return
        if (securePrefs.contains(KEY_PRIVATE_KEY) && securePrefs.contains(KEY_PUBLIC_KEY)) {
            ensureLegacyActiveBackedUp()
            return
        }

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
                .putString(KEY_WALLET_ORIGIN, ORIGIN_IDENTITY_DERIVED)
                .putString(KEY_IDENTITY_FINGERPRINT, currentIdentityFingerprint())
                .apply()
            backupCurrentWalletMaterial()

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

    private fun ensureLegacyActiveBackedUp() {
        try {
            backupCurrentWalletMaterial()
        } catch (_: Exception) {
        }
    }

    private fun backupCurrentWalletMaterial() {
        val privateKeyBase64 = securePrefs.getString(KEY_PRIVATE_KEY, null) ?: return
        val publicKeyBase64 = securePrefs.getString(KEY_PUBLIC_KEY, null) ?: return
        val publicKeyBytes = android.util.Base64.decode(publicKeyBase64, android.util.Base64.NO_WRAP)
        val publicKeyBase58 = SolanaKeyDerivation.encodeBase58(publicKeyBytes)
        val mnemonic = securePrefs.getString(KEY_MNEMONIC, null)
        val origin = securePrefs.getString(KEY_WALLET_ORIGIN, null)
        val identityFingerprint = securePrefs.getString(KEY_IDENTITY_FINGERPRINT, null)

        securePrefs.edit()
            .putString("$STORE_PREFIX_PRIVATE_KEY$publicKeyBase58", privateKeyBase64)
            .putString("$STORE_PREFIX_PUBLIC_KEY$publicKeyBase58", publicKeyBase64)
            .putString("$STORE_PREFIX_ORIGIN$publicKeyBase58", origin)
            .putString("$STORE_PREFIX_IDENTITY_FINGERPRINT$publicKeyBase58", identityFingerprint)
            .apply()

        if (!mnemonic.isNullOrBlank()) {
            securePrefs.edit().putString("$STORE_PREFIX_MNEMONIC$publicKeyBase58", mnemonic).apply()
        } else {
            securePrefs.edit().remove("$STORE_PREFIX_MNEMONIC$publicKeyBase58").apply()
        }
    }

    private fun getStoredWalletMaterial(publicKeyBase58: String): StoredWalletMaterial? {
        val privateKeyBase64 = securePrefs.getString("$STORE_PREFIX_PRIVATE_KEY$publicKeyBase58", null) ?: return null
        val publicKeyBase64 = securePrefs.getString("$STORE_PREFIX_PUBLIC_KEY$publicKeyBase58", null) ?: return null
        return StoredWalletMaterial(
            publicKeyBase58 = publicKeyBase58,
            privateKeyBase64 = privateKeyBase64,
            publicKeyBase64 = publicKeyBase64,
            mnemonic = securePrefs.getString("$STORE_PREFIX_MNEMONIC$publicKeyBase58", null),
            origin = securePrefs.getString("$STORE_PREFIX_ORIGIN$publicKeyBase58", null),
            identityFingerprint = securePrefs.getString("$STORE_PREFIX_IDENTITY_FINGERPRINT$publicKeyBase58", null)
        )
    }

    private fun hasStoredWalletMaterial(publicKeyBase58: String): Boolean {
        return securePrefs.contains("$STORE_PREFIX_PRIVATE_KEY$publicKeyBase58") &&
            securePrefs.contains("$STORE_PREFIX_PUBLIC_KEY$publicKeyBase58")
    }

    private fun applyAsActiveWallet(material: StoredWalletMaterial) {
        securePrefs.edit()
            .putString(KEY_PRIVATE_KEY, material.privateKeyBase64)
            .putString(KEY_PUBLIC_KEY, material.publicKeyBase64)
            .putString(KEY_WALLET_ORIGIN, material.origin)
            .putString(KEY_IDENTITY_FINGERPRINT, material.identityFingerprint)
            .apply()

        if (!material.mnemonic.isNullOrBlank()) {
            securePrefs.edit().putString(KEY_MNEMONIC, material.mnemonic).apply()
        } else {
            securePrefs.edit().remove(KEY_MNEMONIC).apply()
        }

        val privateKeyBytes = android.util.Base64.decode(material.privateKeyBase64, android.util.Base64.NO_WRAP)
        cacheKeypair(privateKeyBytes)
    }

    private fun deleteStoredWalletMaterial(publicKeyBase58: String) {
        securePrefs.edit()
            .remove("$STORE_PREFIX_PRIVATE_KEY$publicKeyBase58")
            .remove("$STORE_PREFIX_PUBLIC_KEY$publicKeyBase58")
            .remove("$STORE_PREFIX_MNEMONIC$publicKeyBase58")
            .remove("$STORE_PREFIX_ORIGIN$publicKeyBase58")
            .remove("$STORE_PREFIX_IDENTITY_FINGERPRINT$publicKeyBase58")
            .apply()
    }

    private fun currentIdentityFingerprint(): String? {
        return try {
            val identityPrivB64 = context
                .getSharedPreferences(IDENTITY_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(IDENTITY_ED25519_PRIVATE_KEY_PREF, null)
                ?: return null
            val identityPrivateKey = android.util.Base64.decode(identityPrivB64, android.util.Base64.DEFAULT)
            if (identityPrivateKey.size != 32) return null
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(identityPrivateKey)
            digest.take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
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

    private fun isWalletStorageUsable(): Boolean {
        if (!secureStorageDegraded) return true
        return allowInsecureStorageFallback
    }

    private fun secureStorageUnavailableMessage(): String {
        return secureStorageErrorMessage ?: "Secure wallet storage unavailable on this device."
    }

    private fun isRobolectricRuntime(): Boolean {
        return try {
            Class.forName("org.robolectric.RuntimeEnvironment")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isHostJvmRuntime(): Boolean {
        val vm = System.getProperty("java.vm.name").orEmpty()
        val isAndroidVm = vm.contains("Dalvik", ignoreCase = true) || vm.contains("ART", ignoreCase = true)
        return !isAndroidVm
    }

}
