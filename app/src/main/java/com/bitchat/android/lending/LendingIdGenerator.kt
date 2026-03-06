package com.bitchat.android.lending

import java.security.SecureRandom

class LendingIdGenerator(
    private val secureRandom: SecureRandom = SecureRandom()
) {
    companion object {
        private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTVWXYZ"
        private const val DEFAULT_LENGTH = 6
        private const val MAX_ATTEMPTS = 32
    }

    fun generate(length: Int = DEFAULT_LENGTH): String {
        require(length in 6..8) { "Lending ID length must be between 6 and 8 characters." }
        val chars = CharArray(length) {
            ALPHABET[secureRandom.nextInt(ALPHABET.length)]
        }
        return String(chars)
    }

    suspend fun generateUniqueId(
        length: Int = DEFAULT_LENGTH,
        isTaken: suspend (String) -> Boolean
    ): String {
        repeat(MAX_ATTEMPTS) {
            val candidate = generate(length)
            if (!isTaken(candidate)) {
                return candidate
            }
        }
        throw IllegalStateException("Failed to generate a unique lending ID after $MAX_ATTEMPTS attempts.")
    }
}
