package com.bitchat.android.lending

import android.util.Base64
import com.bitchat.android.solana.SolanaKeyDerivation
import com.bitchat.android.solana.SolanaTokenAccountUtils
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object SquadsCodec {
    private val ed25519Spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    private val PROPOSAL_CREATE_DISCRIMINATOR = byteArrayOf(
        220.toByte(), 60, 73, 224.toByte(), 30, 108, 79, 159.toByte()
    )
    private val PROPOSAL_APPROVE_DISCRIMINATOR = byteArrayOf(
        144.toByte(), 37, 164.toByte(), 136.toByte(), 188.toByte(), 216.toByte(), 42, 248.toByte()
    )
    private val VAULT_TRANSACTION_CREATE_DISCRIMINATOR = byteArrayOf(
        48, 250.toByte(), 78, 168.toByte(), 208.toByte(), 226.toByte(), 218.toByte(), 211.toByte()
    )
    private val VAULT_TRANSACTION_EXECUTE_DISCRIMINATOR = byteArrayOf(
        194.toByte(), 8, 161.toByte(), 87, 153.toByte(), 164.toByte(), 25, 171.toByte()
    )

    private val SYSTEM_PROGRAM_BYTES = SolanaTokenAccountUtils.decodeBase58(SolanaTokenAccountUtils.SYSTEM_PROGRAM_ID)

    fun getProgramConfigPda(programId: String): String {
        return findProgramAddress(
            programId = programId,
            seeds = listOf("multisig".toByteArray(), "program_config".toByteArray())
        )
    }

    fun getMultisigPda(programId: String, createKey: String): String {
        return findProgramAddress(
            programId = programId,
            seeds = listOf("multisig".toByteArray(), "multisig".toByteArray(), decodeBase58(createKey))
        )
    }

    fun getVaultPda(programId: String, multisigAddress: String, index: Int): String {
        return findProgramAddress(
            programId = programId,
            seeds = listOf(
                "multisig".toByteArray(),
                decodeBase58(multisigAddress),
                "vault".toByteArray(),
                byteArrayOf(index.toByte())
            )
        )
    }

    fun getTransactionPda(programId: String, multisigAddress: String, index: Long): String {
        return findProgramAddress(
            programId = programId,
            seeds = listOf(
                "multisig".toByteArray(),
                decodeBase58(multisigAddress),
                "transaction".toByteArray(),
                longLe(index)
            )
        )
    }

    fun getProposalPda(programId: String, multisigAddress: String, transactionIndex: Long): String {
        return findProgramAddress(
            programId = programId,
            seeds = listOf(
                "multisig".toByteArray(),
                decodeBase58(multisigAddress),
                "transaction".toByteArray(),
                longLe(transactionIndex),
                "proposal".toByteArray()
            )
        )
    }

    fun proposalCreateData(transactionIndex: Long, draft: Boolean = false): ByteArray {
        return PROPOSAL_CREATE_DISCRIMINATOR + longLe(transactionIndex) + byteArrayOf(if (draft) 1 else 0)
    }

    fun proposalApproveData(memo: String? = null): ByteArray {
        return PROPOSAL_APPROVE_DISCRIMINATOR + optionString(memo)
    }

    fun vaultTransactionCreateData(
        vaultIndex: Int,
        ephemeralSigners: Int,
        transactionMessage: ByteArray,
        memo: String? = null
    ): ByteArray {
        return VAULT_TRANSACTION_CREATE_DISCRIMINATOR +
            byteArrayOf(vaultIndex.toByte()) +
            byteArrayOf(ephemeralSigners.toByte()) +
            vecBytes(transactionMessage) +
            optionString(memo)
    }

    fun vaultTransactionExecuteData(): ByteArray = VAULT_TRANSACTION_EXECUTE_DISCRIMINATOR

    fun legacyTransferInstructionMessage(vaultAddress: String, recipientAddress: String, amountLamports: Long): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(1)) // num signers
        output.write(byteArrayOf(1)) // num writable signers
        output.write(byteArrayOf(1)) // num writable non-signers
        output.write(vecPubkeys(listOf(vaultAddress, recipientAddress, SolanaTokenAccountUtils.SYSTEM_PROGRAM_ID)))
        output.write(vecBytes(compiledInstructionSystemTransfer(amountLamports)))
        output.write(emptyVec())
        return output.toByteArray()
    }

    private fun compiledInstructionSystemTransfer(amountLamports: Long): ByteArray {
        val transferData = ByteArray(12)
        transferData[0] = 2
        for (i in 0..7) {
            transferData[4 + i] = ((amountLamports shr (i * 8)) and 0xFF).toByte()
        }
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(2)) // system program index
        output.write(vecU8(byteArrayOf(0, 1))) // vault, recipient
        output.write(vecBytes(transferData))
        return output.toByteArray()
    }

    fun buildSignedLegacyTransaction(
        recentBlockhash: String,
        signerPublicKey: String,
        signer: (ByteArray) -> ByteArray?,
        instructions: List<SquadsInstruction>
    ): String {
        val message = buildLegacyMessage(
            recentBlockhash = recentBlockhash,
            feePayer = signerPublicKey,
            instructions = instructions
        )
        val signature = signer(message) ?: throw IllegalStateException("wallet_signing_failed")
        val transaction = ByteArray(1 + 64 + message.size)
        transaction[0] = 1
        System.arraycopy(signature, 0, transaction, 1, 64)
        System.arraycopy(message, 0, transaction, 65, message.size)
        return Base64.encodeToString(transaction, Base64.NO_WRAP)
    }

    fun parseMultisigState(
        multisigAddress: String,
        dataBase64: String
    ): SquadsMultisigState {
        val data = Base64.decode(dataBase64, Base64.DEFAULT)
        val cursor = Cursor(data)
        cursor.skip(8)
        cursor.skip(32) // create key
        cursor.skip(32) // config authority
        val threshold = cursor.readU16()
        cursor.skip(4) // time lock
        val transactionIndex = cursor.readU64()
        val staleTransactionIndex = cursor.readU64()
        cursor.readOptionPubkey()
        cursor.skip(1) // bump
        val memberCount = cursor.readU32()
        repeat(memberCount) {
            cursor.skip(32)
            cursor.skip(1)
        }
        return SquadsMultisigState(
            multisigAddress = multisigAddress,
            threshold = threshold,
            transactionIndex = transactionIndex,
            staleTransactionIndex = staleTransactionIndex,
            memberCount = memberCount
        )
    }

    fun parseProposalState(
        multisigAddress: String,
        vaultAddress: String,
        proposalAddress: String,
        threshold: Int,
        transactionIndex: Long,
        dataBase64: String,
        txSignature: String? = null
    ): SquadsProposalState {
        val data = Base64.decode(dataBase64, Base64.DEFAULT)
        val cursor = Cursor(data)
        cursor.skip(8)
        cursor.skip(32) // multisig
        cursor.skip(8) // transactionIndex
        val status = cursor.readProposalStatus()
        cursor.skip(1) // bump
        val approved = cursor.readPubkeyVec()
        cursor.readPubkeyVec() // rejected
        cursor.readPubkeyVec() // cancelled
        return SquadsProposalState(
            multisigAddress = multisigAddress,
            vaultAddress = vaultAddress,
            proposalAddress = proposalAddress,
            transactionIndex = transactionIndex,
            approvedCount = approved.size,
            threshold = threshold,
            status = status.kind,
            approvedAt = status.timestampMillis.takeIf { status.kind == SQUADS_PROPOSAL_STATUS_APPROVED },
            executedAt = status.timestampMillis.takeIf { status.kind == SQUADS_PROPOSAL_STATUS_EXECUTED },
            txSignature = txSignature
        )
    }

    private fun buildLegacyMessage(
        recentBlockhash: String,
        feePayer: String,
        instructions: List<SquadsInstruction>
    ): ByteArray {
        val orderedKeys = linkedMapOf<String, MetaRole>()
        orderedKeys[feePayer] = MetaRole(isSigner = true, isWritable = true)
        instructions.forEach { instruction ->
            instruction.accounts.forEach { meta ->
                val existing = orderedKeys[meta.publicKey]
                orderedKeys[meta.publicKey] = if (existing == null) {
                    MetaRole(meta.isSigner, meta.isWritable)
                } else {
                    MetaRole(
                        isSigner = existing.isSigner || meta.isSigner,
                        isWritable = existing.isWritable || meta.isWritable
                    )
                }
            }
            val programExisting = orderedKeys[instruction.programId]
            orderedKeys[instruction.programId] = programExisting ?: MetaRole(isSigner = false, isWritable = false)
        }

        val ordered = orderedKeys.entries.sortedWith(
            compareByDescending<Map.Entry<String, MetaRole>> { it.value.isSigner }
                .thenByDescending { it.value.isWritable }
        )
        val keyIndex = ordered.mapIndexed { index, entry -> entry.key to index }.toMap()

        val numRequiredSignatures = ordered.count { it.value.isSigner }
        val numReadonlySigned = ordered.count { it.value.isSigner && !it.value.isWritable }
        val numReadonlyUnsigned = ordered.count { !it.value.isSigner && !it.value.isWritable }

        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(numRequiredSignatures.toByte(), numReadonlySigned.toByte(), numReadonlyUnsigned.toByte()))
        output.write(compactU16(ordered.size))
        ordered.forEach { output.write(decodeBase58(it.key)) }
        output.write(decodeBase58(recentBlockhash))
        output.write(compactU16(instructions.size))
        instructions.forEach { instruction ->
            output.write(byteArrayOf(keyIndex.getValue(instruction.programId).toByte()))
            output.write(compactU16(instruction.accounts.size))
            instruction.accounts.forEach { meta -> output.write(byteArrayOf(keyIndex.getValue(meta.publicKey).toByte())) }
            output.write(compactU16(instruction.data.size))
            output.write(instruction.data)
        }
        return output.toByteArray()
    }

    private fun vecPubkeys(addresses: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(compactU16(addresses.size))
        addresses.forEach { out.write(decodeBase58(it)) }
        return out.toByteArray()
    }

    private fun vecBytes(bytes: ByteArray): ByteArray = compactU16(bytes.size) + bytes

    private fun emptyVec(): ByteArray = compactU16(0)

    private fun vecU8(bytes: ByteArray): ByteArray = compactU16(bytes.size) + bytes

    private fun optionString(value: String?): ByteArray {
        return if (value == null) {
            byteArrayOf(0)
        } else {
            byteArrayOf(1) + intLe(value.toByteArray().size) + value.toByteArray()
        }
    }

    private fun longLe(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun intLe(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun compactU16(value: Int): ByteArray {
        if (value < 0x80) return byteArrayOf(value.toByte())
        if (value < 0x4000) return byteArrayOf(
            ((value and 0x7F) or 0x80).toByte(),
            ((value shr 7) and 0x7F).toByte()
        )
        return byteArrayOf(
            ((value and 0x7F) or 0x80).toByte(),
            (((value shr 7) and 0x7F) or 0x80).toByte(),
            ((value shr 14) and 0x03).toByte()
        )
    }

    private fun findProgramAddress(programId: String, seeds: List<ByteArray>): String {
        val programBytes = decodeBase58(programId)
        for (bump in 255 downTo 0) {
            val candidate = createProgramAddress(seeds + byteArrayOf(bump.toByte()), programBytes)
            if (!isEd25519CurvePoint(candidate)) {
                return SolanaKeyDerivation.encodeBase58(candidate)
            }
        }
        throw IllegalStateException("unable_to_derive_pda")
    }

    private fun createProgramAddress(seeds: List<ByteArray>, programId: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        seeds.forEach(sha256::update)
        sha256.update(programId)
        sha256.update("ProgramDerivedAddress".toByteArray(Charsets.UTF_8))
        return sha256.digest()
    }

    private fun isEd25519CurvePoint(bytes: ByteArray): Boolean {
        return try {
            EdDSAPublicKeySpec(bytes, ed25519Spec)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeBase58(input: String): ByteArray = SolanaTokenAccountUtils.decodeBase58(input)

    data class SquadsInstruction(
        val programId: String,
        val accounts: List<SquadsAccountMeta>,
        val data: ByteArray
    )

    data class SquadsAccountMeta(
        val publicKey: String,
        val isSigner: Boolean,
        val isWritable: Boolean
    )

    private data class MetaRole(val isSigner: Boolean, val isWritable: Boolean)

    private data class ParsedProposalStatus(val kind: String, val timestampMillis: Long?)

    private class Cursor(private val bytes: ByteArray) {
        private var offset = 0

        fun skip(count: Int) {
            offset += count
        }

        fun readU16(): Int {
            val value = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            offset += 2
            return value
        }

        fun readU32(): Int {
            val value = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            offset += 4
            return value
        }

        fun readU64(): Long {
            val value = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
            offset += 8
            return value
        }

        fun readOptionPubkey(): String? {
            val tag = bytes[offset++].toInt()
            return if (tag == 0) {
                null
            } else {
                val key = bytes.copyOfRange(offset, offset + 32)
                offset += 32
                SolanaKeyDerivation.encodeBase58(key)
            }
        }

        fun readPubkeyVec(): List<String> {
            val size = readU32()
            return List(size) {
                val key = bytes.copyOfRange(offset, offset + 32)
                offset += 32
                SolanaKeyDerivation.encodeBase58(key)
            }
        }

        fun readProposalStatus(): ParsedProposalStatus {
            val variant = bytes[offset++].toInt() and 0xFF
            return when (variant) {
                0 -> ParsedProposalStatus(SQUADS_PROPOSAL_STATUS_DRAFT, readI64Millis())
                1 -> ParsedProposalStatus(SQUADS_PROPOSAL_STATUS_ACTIVE, readI64Millis())
                2 -> ParsedProposalStatus(SQUADS_PROPOSAL_STATUS_REJECTED, readI64Millis())
                3 -> ParsedProposalStatus(SQUADS_PROPOSAL_STATUS_APPROVED, readI64Millis())
                4 -> ParsedProposalStatus("EXECUTING", null)
                5 -> ParsedProposalStatus(SQUADS_PROPOSAL_STATUS_EXECUTED, readI64Millis())
                6 -> ParsedProposalStatus(SQUADS_PROPOSAL_STATUS_CANCELLED, readI64Millis())
                else -> ParsedProposalStatus("UNKNOWN", null)
            }
        }

        private fun readI64Millis(): Long {
            val seconds = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
            offset += 8
            return seconds * 1000L
        }
    }
}
