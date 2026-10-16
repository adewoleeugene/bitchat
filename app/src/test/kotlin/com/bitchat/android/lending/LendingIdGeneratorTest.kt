package com.bitchat.android.lending

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class LendingIdGeneratorTest {

    @Test
    fun generate_returnsUppercaseAmbiguitySafeIdentifier() {
        val generator = LendingIdGenerator(FixedSecureRandom(intArrayOf(0, 1, 2, 3, 4, 5)))

        val lendingId = generator.generate()

        assertEquals("234567", lendingId)
        assertTrue(lendingId.all { it in "23456789ABCDEFGHJKLMNPQRSTVWXYZ" })
    }

    @Test
    fun generateUniqueId_retriesUntilIdentifierIsAvailable() = runBlocking {
        val generator = LendingIdGenerator(
            FixedSecureRandom(
                intArrayOf(
                    0, 0, 0, 0, 0, 0,
                    1, 1, 1, 1, 1, 1
                )
            )
        )

        val taken = mutableSetOf("222222")
        val lendingId = generator.generateUniqueId { candidate -> candidate in taken }

        assertEquals("333333", lendingId)
    }

    private class FixedSecureRandom(
        private val values: IntArray
    ) : SecureRandom() {
        private var index = 0

        override fun nextInt(bound: Int): Int {
            val value = values[index % values.size]
            index += 1
            return value % bound
        }
    }
}
