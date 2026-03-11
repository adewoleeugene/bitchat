package com.bitchat.android.lending

import android.content.Context
import android.util.Base64
import com.bitchat.android.solana.SolanaKeyDerivation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingTreasuryKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "lending_treasury_secure"
        private const val KEY_PREFIX_PRIVATE = "lending_treasury_priv_"
        private const val KEY_PREFIX_PUBLIC = "lending_treasury_pub_"
    }

    private val securePrefs by lazy {
        val masterKey = androidx.security.crypto.MasterKey.Builder(
            context,
            androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS
        ).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()

        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun ensureTreasuryWallet(lendingId: String): TreasuryWalletMaterial {
        getTreasuryWallet(lendingId)?.let { return it }
        val privateKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val publicKey = SolanaKeyDerivation.derivePublicKey(privateKey)
        securePrefs.edit()
            .putString(KEY_PREFIX_PRIVATE + lendingId, Base64.encodeToString(privateKey, Base64.NO_WRAP))
            .putString(KEY_PREFIX_PUBLIC + lendingId, Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .apply()
        return TreasuryWalletMaterial(
            publicKeyBase58 = SolanaKeyDerivation.encodeBase58(publicKey),
            privateKey = privateKey,
            publicKey = publicKey
        )
    }

    fun getTreasuryWallet(lendingId: String): TreasuryWalletMaterial? {
        val privateKeyBase64 = securePrefs.getString(KEY_PREFIX_PRIVATE + lendingId, null) ?: return null
        val publicKeyBase64 = securePrefs.getString(KEY_PREFIX_PUBLIC + lendingId, null) ?: return null
        val privateKey = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
        val publicKey = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        return TreasuryWalletMaterial(
            publicKeyBase58 = SolanaKeyDerivation.encodeBase58(publicKey),
            privateKey = privateKey,
            publicKey = publicKey
        )
    }
}

data class TreasuryWalletMaterial(
    val publicKeyBase58: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray
)
