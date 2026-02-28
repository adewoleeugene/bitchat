package com.bitchat.android.solana

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SolanaKeyDerivationTest {

    @Test
    fun derivesSamePrivateKeyFromSameIdentity() {
        val identity = ByteArray(32) { (it + 1).toByte() }

        val first = SolanaKeyDerivation.derivePrivateKeyFromIdentity(identity)
        val second = SolanaKeyDerivation.derivePrivateKeyFromIdentity(identity)

        assertArrayEquals(first, second)
    }

    @Test
    fun derivesDifferentPrivateKeysFromDifferentIdentities() {
        val a = ByteArray(32) { (it + 1).toByte() }
        val b = ByteArray(32) { (it + 2).toByte() }

        val first = SolanaKeyDerivation.derivePrivateKeyFromIdentity(a)
        val second = SolanaKeyDerivation.derivePrivateKeyFromIdentity(b)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun privateKeyMapsToDisplayedPublicAddress() {
        val identity = ByteArray(32) { (it + 7).toByte() }
        val derivedPrivateKey = SolanaKeyDerivation.derivePrivateKeyFromIdentity(identity)

        val publicFromPrivate = SolanaKeyDerivation.derivePublicKey(derivedPrivateKey)
        val addressFromPrivate = SolanaKeyDerivation.encodeBase58(publicFromPrivate)

        val keypair = SolanaKeyDerivation.deriveKeypair(derivedPrivateKey)
        val displayedAddress = SolanaKeyDerivation.encodeBase58(keypair.second)

        assertEquals(displayedAddress, addressFromPrivate)
    }
}
