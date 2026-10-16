package com.bitchat.android.lending

import com.bitchat.android.solana.SolanaKeyDerivation
import com.bitchat.android.solana.SolanaTokenAccountUtils
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SquadsCodecTest {

    @Test
    fun multisigCreateV2Data_encodesThresholdTimeLockAndMembers() {
        val memberOne = walletAddressFromSeed(11)
        val memberTwo = walletAddressFromSeed(22)
        val memberThree = walletAddressFromSeed(33)

        val encoded = SquadsCodec.multisigCreateV2Data(
            threshold = 2,
            members = listOf(
                SquadsCodec.SquadsMember(memberOne),
                SquadsCodec.SquadsMember(memberTwo, permissionsMask = 2),
                SquadsCodec.SquadsMember(memberThree)
            ),
            timeLockSeconds = 3600
        )

        var offset = 0
        assertArrayEquals(
            byteArrayOf(50, 221.toByte(), 199.toByte(), 93, 40, 245.toByte(), 154.toByte(), 162.toByte()),
            encoded.copyOfRange(offset, offset + 8)
        )
        offset += 8
        assertEquals(0, encoded[offset].toInt())
        offset += 1
        assertEquals(2, readLeShort(encoded, offset))
        offset += 2
        assertEquals(3600, readLeInt(encoded, offset))
        offset += 4
        assertEquals(3, readLeInt(encoded, offset))
        offset += 4

        assertArrayEquals(SolanaTokenAccountUtils.decodeBase58(memberOne), encoded.copyOfRange(offset, offset + 32))
        offset += 32
        assertEquals(7, encoded[offset].toInt() and 0xFF)
        offset += 1

        assertArrayEquals(SolanaTokenAccountUtils.decodeBase58(memberTwo), encoded.copyOfRange(offset, offset + 32))
        offset += 32
        assertEquals(2, encoded[offset].toInt() and 0xFF)
        offset += 1

        assertArrayEquals(SolanaTokenAccountUtils.decodeBase58(memberThree), encoded.copyOfRange(offset, offset + 32))
        offset += 32
        assertEquals(7, encoded[offset].toInt() and 0xFF)
        offset += 1

        assertEquals(0, encoded[offset].toInt())
        offset += 1
        assertEquals(0, encoded[offset].toInt())
    }

    @Test
    fun buildSignedLegacyTransaction_ordersMultipleSignaturesByMessageSignerOrder() {
        val creatorPrivateKey = ByteArray(32) { 1 }
        val createKeyPrivateKey = ByteArray(32) { 2 }
        val creatorPublicKey = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(creatorPrivateKey))
        val createKeyPublicKey = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(createKeyPrivateKey))
        val multisigAddress = SquadsCodec.getMultisigPda(DEFAULT_SQUADS_V4_PROGRAM_ID, createKeyPublicKey)
        val treasuryAddress = walletAddressFromSeed(44)

        val encodedTransaction = SquadsCodec.buildSignedLegacyTransaction(
            recentBlockhash = walletAddressFromSeed(55),
            signers = listOf(
                SquadsCodec.SignedMessageSigner(createKeyPublicKey) { message ->
                    sign(message, createKeyPrivateKey)
                },
                SquadsCodec.SignedMessageSigner(creatorPublicKey) { message ->
                    sign(message, creatorPrivateKey)
                }
            ),
            instructions = listOf(
                SquadsCodec.SquadsInstruction(
                    programId = DEFAULT_SQUADS_V4_PROGRAM_ID,
                    accounts = listOf(
                        SquadsCodec.SquadsAccountMeta(
                            SquadsCodec.getProgramConfigPda(DEFAULT_SQUADS_V4_PROGRAM_ID),
                            isSigner = false,
                            isWritable = false
                        ),
                        SquadsCodec.SquadsAccountMeta(createKeyPublicKey, isSigner = true, isWritable = false),
                        SquadsCodec.SquadsAccountMeta(creatorPublicKey, isSigner = true, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(multisigAddress, isSigner = false, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(SolanaTokenAccountUtils.SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
                        SquadsCodec.SquadsAccountMeta(treasuryAddress, isSigner = false, isWritable = true)
                    ),
                    data = SquadsCodec.multisigCreateV2Data(
                        threshold = 2,
                        members = listOf(
                            SquadsCodec.SquadsMember(creatorPublicKey),
                            SquadsCodec.SquadsMember(walletAddressFromSeed(66)),
                            SquadsCodec.SquadsMember(walletAddressFromSeed(77))
                        )
                    )
                )
            )
        )

        val transaction = Base64.getDecoder().decode(encodedTransaction)
        assertEquals(2, transaction[0].toInt())

        val firstSignature = transaction.copyOfRange(1, 65)
        val secondSignature = transaction.copyOfRange(65, 129)
        val message = transaction.copyOfRange(129, transaction.size)

        assertEquals(2, message[0].toInt())
        val signerOrder = readSignerPublicKeys(message)
        val expectedSignatures = signerOrder.map { signerPublicKey ->
            when (signerPublicKey) {
                creatorPublicKey -> sign(message, creatorPrivateKey)
                createKeyPublicKey -> sign(message, createKeyPrivateKey)
                else -> throw AssertionError("unexpected signer order entry: $signerPublicKey")
            }
        }
        assertArrayEquals(expectedSignatures[0], firstSignature)
        assertArrayEquals(expectedSignatures[1], secondSignature)
    }

    private fun walletAddressFromSeed(seedByte: Byte): String {
        return SolanaKeyDerivation.encodeBase58(
            SolanaKeyDerivation.derivePublicKey(ByteArray(32) { seedByte })
        )
    }

    private fun sign(message: ByteArray, privateKeyBytes: ByteArray): ByteArray {
        val spec = EdDSAPrivateKeySpec(
            privateKeyBytes,
            EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        )
        val signer = EdDSAEngine()
        signer.initSign(EdDSAPrivateKey(spec))
        signer.update(message)
        return signer.sign()
    }

    private fun readLeShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readSignerPublicKeys(message: ByteArray): List<String> {
        val signerCount = message[0].toInt() and 0xFF
        var offset = 3
        val accountCountInfo = readCompactU16(message, offset)
        offset += accountCountInfo.second
        return buildList {
            repeat(signerCount) {
                add(
                    SolanaKeyDerivation.encodeBase58(
                        message.copyOfRange(offset, offset + 32)
                    )
                )
                offset += 32
            }
        }
    }

    private fun readCompactU16(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val first = bytes[offset].toInt() and 0xFF
        if ((first and 0x80) == 0) return first to 1
        val second = bytes[offset + 1].toInt() and 0xFF
        if ((second and 0x80) == 0) {
            return ((first and 0x7F) or ((second and 0x7F) shl 7)) to 2
        }
        val third = bytes[offset + 2].toInt() and 0xFF
        return ((first and 0x7F) or ((second and 0x7F) shl 7) or ((third and 0x03) shl 14)) to 3
    }
}
