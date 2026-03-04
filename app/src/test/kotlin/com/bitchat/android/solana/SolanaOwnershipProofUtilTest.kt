package com.bitchat.android.solana

import com.bitchat.android.model.SolanaOwnershipProof
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaOwnershipProofUtilTest {

    @Test
    fun verifyProof_acceptsValidProof() {
        val nickname = "alice"
        val signingPublicKey = ByteArray(32) { (it + 1).toByte() }
        val walletPrivateKey = ByteArray(32) { (it + 20).toByte() }
        val solanaAddress = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(walletPrivateKey))
        val now = 1_735_000_000_000L

        val unsignedProof = SolanaOwnershipProof(
            claimType = SolanaOwnershipProof.ClaimType.SPL_TOKEN,
            targetAddress = "So11111111111111111111111111111111111111112",
            minRequired = 100,
            observedBalance = 250,
            validatedAtMs = now,
            expiresAtMs = now + 60_000L,
            signature = ByteArray(64)
        )

        val signature = sign(
            privateKey = walletPrivateKey,
            data = SolanaOwnershipProofUtil.buildProofMessage(
                nickname = nickname,
                solanaAddress = solanaAddress,
                signingPublicKey = signingPublicKey,
                proof = unsignedProof
            )
        )
        val signedProof = unsignedProof.copy(signature = signature)

        val verified = SolanaOwnershipProofUtil.verifyProof(
            nickname = nickname,
            solanaAddress = solanaAddress,
            signingPublicKey = signingPublicKey,
            proof = signedProof,
            nowMs = now + 1_000L
        )

        assertTrue(verified)
    }

    @Test
    fun verifyProof_rejectsExpiredProof() {
        val nickname = "alice"
        val signingPublicKey = ByteArray(32) { (it + 2).toByte() }
        val walletPrivateKey = ByteArray(32) { (it + 21).toByte() }
        val solanaAddress = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(walletPrivateKey))
        val now = 1_735_000_000_000L

        val unsignedProof = SolanaOwnershipProof(
            claimType = SolanaOwnershipProof.ClaimType.SPL_TOKEN,
            targetAddress = "So11111111111111111111111111111111111111112",
            minRequired = 1,
            observedBalance = 1,
            validatedAtMs = now - 120_000L,
            expiresAtMs = now - 60_000L,
            signature = ByteArray(64)
        )

        val signature = sign(
            privateKey = walletPrivateKey,
            data = SolanaOwnershipProofUtil.buildProofMessage(
                nickname = nickname,
                solanaAddress = solanaAddress,
                signingPublicKey = signingPublicKey,
                proof = unsignedProof
            )
        )
        val signedProof = unsignedProof.copy(signature = signature)

        val verified = SolanaOwnershipProofUtil.verifyProof(
            nickname = nickname,
            solanaAddress = solanaAddress,
            signingPublicKey = signingPublicKey,
            proof = signedProof,
            nowMs = now
        )

        assertFalse(verified)
    }

    @Test
    fun verifyProof_rejectsTamperedFields() {
        val nickname = "alice"
        val signingPublicKey = ByteArray(32) { (it + 3).toByte() }
        val walletPrivateKey = ByteArray(32) { (it + 22).toByte() }
        val solanaAddress = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(walletPrivateKey))
        val now = 1_735_000_000_000L

        val unsignedProof = SolanaOwnershipProof(
            claimType = SolanaOwnershipProof.ClaimType.SPL_TOKEN,
            targetAddress = "So11111111111111111111111111111111111111112",
            minRequired = 500,
            observedBalance = 750,
            validatedAtMs = now,
            expiresAtMs = now + 120_000L,
            signature = ByteArray(64)
        )
        val signature = sign(
            privateKey = walletPrivateKey,
            data = SolanaOwnershipProofUtil.buildProofMessage(
                nickname = nickname,
                solanaAddress = solanaAddress,
                signingPublicKey = signingPublicKey,
                proof = unsignedProof
            )
        )
        val tamperedProof = unsignedProof.copy(
            minRequired = unsignedProof.minRequired + 1,
            signature = signature
        )

        val verified = SolanaOwnershipProofUtil.verifyProof(
            nickname = nickname,
            solanaAddress = solanaAddress,
            signingPublicKey = signingPublicKey,
            proof = tamperedProof,
            nowMs = now + 1_000L
        )

        assertFalse(verified)
    }

    @Test
    fun verifyProof_rejectsWrongSigner() {
        val nickname = "alice"
        val signingPublicKey = ByteArray(32) { (it + 4).toByte() }
        val walletPrivateKey = ByteArray(32) { (it + 23).toByte() }
        val wrongPrivateKey = ByteArray(32) { (it + 88).toByte() }
        val solanaAddress = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(walletPrivateKey))
        val now = 1_735_000_000_000L

        val unsignedProof = SolanaOwnershipProof(
            claimType = SolanaOwnershipProof.ClaimType.NFT_COLLECTION,
            targetAddress = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
            minRequired = 1,
            observedBalance = 1,
            validatedAtMs = now,
            expiresAtMs = now + 120_000L,
            signature = ByteArray(64)
        )
        val wrongSignature = sign(
            privateKey = wrongPrivateKey,
            data = SolanaOwnershipProofUtil.buildProofMessage(
                nickname = nickname,
                solanaAddress = solanaAddress,
                signingPublicKey = signingPublicKey,
                proof = unsignedProof
            )
        )
        val signedByWrongKey = unsignedProof.copy(signature = wrongSignature)

        val verified = SolanaOwnershipProofUtil.verifyProof(
            nickname = nickname,
            solanaAddress = solanaAddress,
            signingPublicKey = signingPublicKey,
            proof = signedByWrongKey,
            nowMs = now + 1_000L
        )

        assertFalse(verified)
    }

    @Test
    fun verifyProof_rejectsObservedBalanceBelowMinimum() {
        val nickname = "alice"
        val signingPublicKey = ByteArray(32) { (it + 5).toByte() }
        val walletPrivateKey = ByteArray(32) { (it + 24).toByte() }
        val solanaAddress = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(walletPrivateKey))
        val now = 1_735_000_000_000L

        val unsignedProof = SolanaOwnershipProof(
            claimType = SolanaOwnershipProof.ClaimType.SPL_TOKEN,
            targetAddress = "So11111111111111111111111111111111111111112",
            minRequired = 500,
            observedBalance = 499,
            validatedAtMs = now,
            expiresAtMs = now + 120_000L,
            signature = ByteArray(64)
        )
        val signature = sign(
            privateKey = walletPrivateKey,
            data = SolanaOwnershipProofUtil.buildProofMessage(
                nickname = nickname,
                solanaAddress = solanaAddress,
                signingPublicKey = signingPublicKey,
                proof = unsignedProof
            )
        )
        val signedProof = unsignedProof.copy(signature = signature)

        val verified = SolanaOwnershipProofUtil.verifyProof(
            nickname = nickname,
            solanaAddress = solanaAddress,
            signingPublicKey = signingPublicKey,
            proof = signedProof,
            nowMs = now + 1_000L
        )

        assertFalse(verified)
    }

    @Test
    fun verifyProof_rejectsProofWindowTooLong() {
        val nickname = "alice"
        val signingPublicKey = ByteArray(32) { (it + 6).toByte() }
        val walletPrivateKey = ByteArray(32) { (it + 25).toByte() }
        val solanaAddress = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(walletPrivateKey))
        val now = 1_735_000_000_000L

        val unsignedProof = SolanaOwnershipProof(
            claimType = SolanaOwnershipProof.ClaimType.NFT_COLLECTION,
            targetAddress = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
            minRequired = 1,
            observedBalance = 1,
            validatedAtMs = now,
            expiresAtMs = now + (25 * 60 * 60 * 1000L),
            signature = ByteArray(64)
        )
        val signature = sign(
            privateKey = walletPrivateKey,
            data = SolanaOwnershipProofUtil.buildProofMessage(
                nickname = nickname,
                solanaAddress = solanaAddress,
                signingPublicKey = signingPublicKey,
                proof = unsignedProof
            )
        )
        val signedProof = unsignedProof.copy(signature = signature)

        val verified = SolanaOwnershipProofUtil.verifyProof(
            nickname = nickname,
            solanaAddress = solanaAddress,
            signingPublicKey = signingPublicKey,
            proof = signedProof,
            nowMs = now + 1_000L
        )

        assertFalse(verified)
    }

    private fun sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val key = EdDSAPrivateKey(EdDSAPrivateKeySpec(privateKey, spec))
        val signer = EdDSAEngine()
        signer.initSign(key)
        signer.update(data)
        return signer.sign()
    }
}
