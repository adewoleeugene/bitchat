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
    private const val PERMISSION_INITIATE = 1
    private const val PERMISSION_VOTE = 2
    private const val PERMISSION_EXECUTE = 4

    private val MULTISIG_CREATE_V2_DISCRIMINATOR = byteArrayOf(
        50, 221.toByte(), 199.toByte(), 93, 40, 245.toByte(), 154.toByte(), 162.toByte()
    )
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
            seeds = listOf("multisig".toByteArray(), decodeBase58(createKey))
        )
    }

    fun getVaultPda(programId: String, multisigAddress: String, index: Int): String {
        return findProgramAddress(
            programId = programId,
            seeds = listOf(
                "vault".toByteArray(),
                decodeBase58(multisigAddress),
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

    fun multisigCreateV2Data(
        threshold: Int,
        members: List<SquadsMember>,
        configAuthority: String? = null,
        timeLockSeconds: Int = 0,
        rentCollector: String? = null,
        memo: String? = null
    ): ByteArray {
        return MULTISIG_CREATE_V2_DISCRIMINATOR +
            optionPubkey(configAuthority) +
            shortLe(threshold) +
            intLe(timeLockSeconds) +
            membersVec(members) +
            optionPubkey(rentCollector) +
            optionString(memo)
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

    /**
     * Build a vault transaction message for SPL token transfer (transfer_checked).
     * The vault is the token authority (signer). Accounts in order:
     * 0 = vault (signer), 1 = source ATA, 2 = mint, 3 = destination ATA, 4 = token program
     */
    fun legacySplTransferInstructionMessage(
        vaultAddress: String,
        sourceTokenAccount: String,
        mintAddress: String,
        destinationTokenAccount: String,
        amountAtomic: Long,
        decimals: Int
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(1)) // num signers (vault)
        output.write(byteArrayOf(0)) // num writable signers that are not the vault itself (source ATA handled separately)
        output.write(byteArrayOf(2)) // num writable non-signers (source ATA, destination ATA)
        output.write(vecPubkeys(listOf(
            vaultAddress,
            sourceTokenAccount,
            destinationTokenAccount,
            mintAddress,
            SolanaTokenAccountUtils.TOKEN_PROGRAM_ID
        )))
        output.write(vecBytes(compiledInstructionSplTransferChecked(amountAtomic, decimals)))
        output.write(emptyVec()) // address table lookups
        return output.toByteArray()
    }

    private fun compiledInstructionSplTransferChecked(amountAtomic: Long, decimals: Int): ByteArray {
        // SPL Token transfer_checked instruction index = 12
        val transferData = ByteArray(10)
        transferData[0] = 12 // transfer_checked
        for (i in 0..7) {
            transferData[1 + i] = ((amountAtomic shr (i * 8)) and 0xFF).toByte()
        }
        transferData[9] = decimals.toByte()
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(4)) // token program index (index 4 in account list)
        output.write(vecU8(byteArrayOf(1, 3, 2, 0))) // source ATA, mint, destination ATA, vault (authority)
        output.write(vecBytes(transferData))
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
        return buildSignedLegacyTransaction(
            recentBlockhash = recentBlockhash,
            signers = listOf(SignedMessageSigner(signerPublicKey, signer)),
            instructions = instructions
        )
    }

    fun buildSignedLegacyTransaction(
        recentBlockhash: String,
        signers: List<SignedMessageSigner>,
        instructions: List<SquadsInstruction>
    ): String {
        val message = buildLegacyMessage(
            recentBlockhash = recentBlockhash,
            feePayer = signers.firstOrNull()?.publicKey
                ?: throw IllegalArgumentException("signer_required"),
            instructions = instructions
        )
        val signerOrder = readSignerOrder(message)
        val signerMap = signers.associateBy { it.publicKey }
        val signatures = signerOrder.map { publicKey ->
            val signer = signerMap[publicKey] ?: throw IllegalStateException("missing_signature_for_$publicKey")
            signer.sign(message) ?: throw IllegalStateException("wallet_signing_failed")
        }
        val transaction = ByteArray(1 + (64 * signatures.size) + message.size)
        transaction[0] = signatures.size.toByte()
        var offset = 1
        signatures.forEach { signature ->
            System.arraycopy(signature, 0, transaction, offset, 64)
            offset += 64
        }
        System.arraycopy(message, 0, transaction, offset, message.size)
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

    fun parseProgramConfigState(dataBase64: String): SquadsProgramConfigState {
        val data = Base64.decode(dataBase64, Base64.DEFAULT)
        val cursor = Cursor(data)
        cursor.skip(8)
        cursor.skip(32) // authority
        val multisigCreationFeeLamports = cursor.readU64()
        val treasuryAddress = cursor.readPubkey()
        return SquadsProgramConfigState(
            treasuryAddress = treasuryAddress,
            multisigCreationFeeLamports = multisigCreationFeeLamports
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

    private fun membersVec(members: List<SquadsMember>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intLe(members.size))
        members.forEach { member ->
            out.write(decodeBase58(member.publicKey))
            out.write(byteArrayOf(member.permissionsMask.toByte()))
        }
        return out.toByteArray()
    }

    private fun optionPubkey(value: String?): ByteArray {
        return if (value.isNullOrBlank()) {
            byteArrayOf(0)
        } else {
            byteArrayOf(1) + decodeBase58(value)
        }
    }

    private fun optionString(value: String?): ByteArray {
        return if (value == null) {
            byteArrayOf(0)
        } else {
            byteArrayOf(1) + intLe(value.toByteArray().size) + value.toByteArray()
        }
    }

    private fun longLe(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun intLe(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortLe(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

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

    data class SquadsMember(
        val publicKey: String,
        val permissionsMask: Int = PERMISSION_INITIATE or PERMISSION_VOTE or PERMISSION_EXECUTE
    )

    data class SignedMessageSigner(
        val publicKey: String,
        val sign: (ByteArray) -> ByteArray?
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
                readPubkey()
            }
        }

        fun readPubkeyVec(): List<String> {
            val size = readU32()
            return List(size) {
                readPubkey()
            }
        }

        fun readPubkey(): String {
            val key = bytes.copyOfRange(offset, offset + 32)
            offset += 32
            return SolanaKeyDerivation.encodeBase58(key)
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

    private fun readSignerOrder(message: ByteArray): List<String> {
        val numRequiredSignatures = message[0].toInt() and 0xFF
        var offset = 3
        val accountCount = readCompactU16(message, offset)
        offset += accountCount.second
        return buildList {
            repeat(accountCount.first) {
                val key = message.copyOfRange(offset, offset + 32)
                offset += 32
                if (size < numRequiredSignatures) {
                    add(SolanaKeyDerivation.encodeBase58(key))
                }
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
