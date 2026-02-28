package com.bitchat.android.solana

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic Solana key derivation from the app Ed25519 identity key.
 */
object SolanaKeyDerivation {
    private const val DERIVE_INFO = "bitchat-solana-v1"
    private const val DERIVE_SALT = "bitchat-solana-identity-salt"
    private val ed25519Spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    fun derivePrivateKeyFromIdentity(identityPrivateKey: ByteArray): ByteArray {
        require(identityPrivateKey.size == 32) { "Identity private key must be 32 bytes" }
        return hkdfSha256(
            ikm = identityPrivateKey,
            salt = DERIVE_SALT.toByteArray(Charsets.UTF_8),
            info = DERIVE_INFO.toByteArray(Charsets.UTF_8),
            outputLen = 32
        )
    }

    fun derivePublicKey(privateKeyBytes: ByteArray): ByteArray {
        val privKeySpec = EdDSAPrivateKeySpec(privateKeyBytes, ed25519Spec)
        val privKey = EdDSAPrivateKey(privKeySpec)
        val pubKeySpec = EdDSAPublicKeySpec(privKey.a, ed25519Spec)
        val pubKey = EdDSAPublicKey(pubKeySpec)
        return pubKey.abyte
    }

    fun deriveKeypair(privateKeyBytes: ByteArray): Pair<ByteArray, ByteArray> {
        return Pair(privateKeyBytes, derivePublicKey(privateKeyBytes))
    }

    fun encodeBase58(input: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        if (input.isEmpty()) return ""

        var zeros = 0
        for (b in input) {
            if (b.toInt() == 0) zeros++ else break
        }

        val encoded = StringBuilder()
        var num = java.math.BigInteger(1, input)
        val base = java.math.BigInteger.valueOf(58)
        while (num > java.math.BigInteger.ZERO) {
            val (quotient, remainder) = num.divideAndRemainder(base)
            encoded.append(alphabet[remainder.toInt()])
            num = quotient
        }

        repeat(zeros) { encoded.append('1') }
        return encoded.reverse().toString()
    }

    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLen: Int
    ): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = hmac.doFinal(ikm)

        val output = ByteArray(outputLen)
        var generated = 0
        var previous = ByteArray(0)
        var counter: Byte = 1

        while (generated < outputLen) {
            val blockMac = Mac.getInstance("HmacSHA256")
            blockMac.init(SecretKeySpec(prk, "HmacSHA256"))
            blockMac.update(previous)
            blockMac.update(info)
            blockMac.update(counter)
            val block = blockMac.doFinal()

            val toCopy = minOf(block.size, outputLen - generated)
            System.arraycopy(block, 0, output, generated, toCopy)
            generated += toCopy
            previous = block
            counter++
        }
        return output
    }
}
