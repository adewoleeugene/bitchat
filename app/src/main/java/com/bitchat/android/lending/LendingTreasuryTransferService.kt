package com.bitchat.android.lending

import android.util.Base64
import com.bitchat.android.solana.SolanaRpcService
import com.bitchat.android.solana.SolanaTokenAccountUtils
import com.bitchat.android.solana.SolanaWalletService
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingTreasuryTransferService @Inject constructor(
    private val rpcService: SolanaRpcService,
    private val walletService: SolanaWalletService
) {
    companion object {
        private const val TOKEN_TRANSFER_CHECKED_INSTRUCTION_INDEX: Byte = 12
        private const val CREATE_ASSOCIATED_TOKEN_ACCOUNT_INSTRUCTION_INDEX: Byte = 0
        private const val NATIVE_SOL_TRANSFER_FEE_BUFFER_LAMPORTS = 5_000L
    }

    private val ed25519Spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    suspend fun sendSplFromTreasury(
        treasuryPrivateKey: ByteArray,
        treasuryOwnerPublicKey: String,
        sourceTokenAccount: String,
        recipientOwnerPublicKey: String,
        mintAddress: String,
        amountAtomic: Long,
        decimals: Int
    ): Result<String> {
        val blockhash = rpcService.getLatestBlockhash().getOrElse { return Result.failure(it) }
        val existingDestinationTokenAccount = rpcService.getTokenAccountAddress(recipientOwnerPublicKey, mintAddress)
            .getOrElse { return Result.failure(it) }
        val destinationTokenAccount = existingDestinationTokenAccount
            ?: SolanaTokenAccountUtils.findAssociatedTokenAddress(recipientOwnerPublicKey, mintAddress)
        val message = buildSplTokenTransferMessage(
            payerOwnerPublicKey = treasuryOwnerPublicKey,
            ownerPublicKey = treasuryOwnerPublicKey,
            sourceTokenAccount = sourceTokenAccount,
            recipientOwnerPublicKey = recipientOwnerPublicKey,
            mintAddress = mintAddress,
            destinationTokenAccount = destinationTokenAccount,
            recentBlockhash = blockhash.blockhash,
            amountAtomic = amountAtomic,
            decimals = decimals,
            createDestinationAta = existingDestinationTokenAccount == null
        )
        val signatureBytes = signWithPrivateKey(treasuryPrivateKey, message)
            ?: return Result.failure(IllegalStateException("treasury_sign_failed"))
        val transaction = ByteArray(1 + 64 + message.size)
        transaction[0] = 1
        System.arraycopy(signatureBytes, 0, transaction, 1, 64)
        System.arraycopy(message, 0, transaction, 65, message.size)
        val signedTxBase64 = Base64.encodeToString(transaction, Base64.NO_WRAP)
        val signature = rpcService.sendTransaction(signedTxBase64).getOrElse { return Result.failure(it) }
        repeat(20) {
            val confirmed = rpcService.confirmTransaction(signature).getOrDefault(false)
            if (confirmed) return Result.success(signature)
            kotlinx.coroutines.delay(1_500L)
        }
        return Result.failure(IllegalStateException("treasury_transfer_confirmation_timeout"))
    }

    suspend fun sendSolFromTreasury(
        treasuryPrivateKey: ByteArray,
        treasuryOwnerPublicKey: String,
        recipientPublicKey: String,
        amountLamports: Long
    ): Result<String> {
        if (amountLamports <= 0L) {
            return Result.failure(IllegalArgumentException("amount_must_be_positive"))
        }
        val treasuryBalance = rpcService.getBalance(treasuryOwnerPublicKey).getOrElse { return Result.failure(it) }
        val maxSendable = (treasuryBalance - NATIVE_SOL_TRANSFER_FEE_BUFFER_LAMPORTS).coerceAtLeast(0L)
        if (maxSendable <= 0L) {
            return Result.failure(IllegalStateException("treasury_balance_insufficient_for_fee"))
        }
        val sendAmount = minOf(amountLamports, maxSendable)
        val blockhash = rpcService.getLatestBlockhash().getOrElse { return Result.failure(it) }
        val message = buildNativeSolTransferMessage(
            senderPublicKey = treasuryOwnerPublicKey,
            recipientPublicKey = recipientPublicKey,
            amountLamports = sendAmount,
            recentBlockhash = blockhash.blockhash
        )
        val signatureBytes = signWithPrivateKey(treasuryPrivateKey, message)
            ?: return Result.failure(IllegalStateException("treasury_sign_failed"))
        val transaction = ByteArray(1 + 64 + message.size)
        transaction[0] = 1
        System.arraycopy(signatureBytes, 0, transaction, 1, 64)
        System.arraycopy(message, 0, transaction, 65, message.size)
        val signedTxBase64 = Base64.encodeToString(transaction, Base64.NO_WRAP)
        val signature = rpcService.sendTransaction(signedTxBase64).getOrElse { return Result.failure(it) }
        repeat(20) {
            val confirmed = rpcService.confirmTransaction(signature).getOrDefault(false)
            if (confirmed) return Result.success(signature)
            kotlinx.coroutines.delay(1_500L)
        }
        return Result.failure(IllegalStateException("treasury_transfer_confirmation_timeout"))
    }

    private fun signWithPrivateKey(privateKeyBytes: ByteArray, data: ByteArray): ByteArray? {
        return try {
            val privateKeySpec = EdDSAPrivateKeySpec(privateKeyBytes, ed25519Spec)
            val privateKey = EdDSAPrivateKey(privateKeySpec)
            val signer = EdDSAEngine()
            signer.initSign(privateKey)
            signer.update(data)
            signer.sign()
        } catch (_: Exception) {
            null
        }
    }

    private fun buildSplTokenTransferMessage(
        payerOwnerPublicKey: String,
        ownerPublicKey: String,
        sourceTokenAccount: String,
        recipientOwnerPublicKey: String,
        mintAddress: String,
        destinationTokenAccount: String,
        recentBlockhash: String,
        amountAtomic: Long,
        decimals: Int,
        createDestinationAta: Boolean
    ): ByteArray {
        val payerOwnerPubKey = SolanaTokenAccountUtils.decodeBase58(payerOwnerPublicKey)
        val ownerPubKey = SolanaTokenAccountUtils.decodeBase58(ownerPublicKey)
        val sourceTokenAccountBytes = SolanaTokenAccountUtils.decodeBase58(sourceTokenAccount)
        val recipientOwnerPubKey = SolanaTokenAccountUtils.decodeBase58(recipientOwnerPublicKey)
        val mintPubKey = SolanaTokenAccountUtils.decodeBase58(mintAddress)
        val destinationTokenAccountBytes = SolanaTokenAccountUtils.decodeBase58(destinationTokenAccount)
        val tokenProgramId = SolanaTokenAccountUtils.decodeBase58(SolanaTokenAccountUtils.TOKEN_PROGRAM_ID)
        val associatedTokenProgramId = SolanaTokenAccountUtils.decodeBase58(SolanaTokenAccountUtils.ASSOCIATED_TOKEN_PROGRAM_ID)
        val systemProgramId = SolanaTokenAccountUtils.decodeBase58(SolanaTokenAccountUtils.SYSTEM_PROGRAM_ID)
        val recentBlockhashBytes = SolanaTokenAccountUtils.decodeBase58(recentBlockhash)

        val transferInstructionData = ByteArray(10)
        transferInstructionData[0] = TOKEN_TRANSFER_CHECKED_INSTRUCTION_INDEX
        for (i in 0..7) {
            transferInstructionData[1 + i] = ((amountAtomic shr (i * 8)) and 0xFF).toByte()
        }
        transferInstructionData[9] = decimals.toByte()

        val accountKeyList = mutableListOf<ByteArray>()
        fun addKey(key: ByteArray): Int {
            val existing = accountKeyList.indexOfFirst { it.contentEquals(key) }
            if (existing >= 0) return existing
            accountKeyList += key
            return accountKeyList.lastIndex
        }

        val payerIndex = addKey(payerOwnerPubKey)
        val ownerIndex = addKey(ownerPubKey)
        val sourceIndex = addKey(sourceTokenAccountBytes)
        val mintIndex = addKey(mintPubKey)
        val destinationIndex = addKey(destinationTokenAccountBytes)
        val recipientOwnerIndex = addKey(recipientOwnerPubKey)
        val tokenProgramIndex = addKey(tokenProgramId)
        val associatedTokenProgramIndex = addKey(associatedTokenProgramId)
        val systemProgramIndex = addKey(systemProgramId)

        val instructions = mutableListOf<ByteArray>()
        if (createDestinationAta) {
            val ataAccounts = byteArrayOf(
                payerIndex.toByte(),
                destinationIndex.toByte(),
                recipientOwnerIndex.toByte(),
                mintIndex.toByte(),
                systemProgramIndex.toByte(),
                tokenProgramIndex.toByte()
            )
            instructions += byteArrayOf(
                associatedTokenProgramIndex.toByte(),
                ataAccounts.size.toByte()
            ) + ataAccounts + compactU16(1) + byteArrayOf(CREATE_ASSOCIATED_TOKEN_ACCOUNT_INSTRUCTION_INDEX)
        }
        val transferAccounts = byteArrayOf(sourceIndex.toByte(), mintIndex.toByte(), destinationIndex.toByte(), ownerIndex.toByte())
        instructions += byteArrayOf(
            tokenProgramIndex.toByte(),
            transferAccounts.size.toByte()
        ) + transferAccounts + compactU16(transferInstructionData.size) + transferInstructionData

        val header = byteArrayOf(1, 0, 3)
        val numAccounts = compactU16(accountKeyList.size)
        val accountKeys = accountKeyList.fold(ByteArray(0)) { acc, key -> acc + key }
        val numInstructions = compactU16(instructions.size)

        return header +
            numAccounts +
            accountKeys +
            recentBlockhashBytes +
            numInstructions +
            instructions.fold(ByteArray(0)) { acc, instruction -> acc + instruction }
    }

    private fun buildNativeSolTransferMessage(
        senderPublicKey: String,
        recipientPublicKey: String,
        amountLamports: Long,
        recentBlockhash: String
    ): ByteArray {
        val senderPubKey = SolanaTokenAccountUtils.decodeBase58(senderPublicKey)
        val recipientPubKey = SolanaTokenAccountUtils.decodeBase58(recipientPublicKey)
        val systemProgramId = ByteArray(32)
        val recentBlockhashBytes = SolanaTokenAccountUtils.decodeBase58(recentBlockhash)

        val instructionData = ByteArray(12)
        instructionData[0] = 2
        for (i in 0..7) {
            instructionData[4 + i] = ((amountLamports shr (i * 8)) and 0xFF).toByte()
        }

        return buildNativeTransferTransactionMessage(
            senderPubKey = senderPubKey,
            recipientPubKey = recipientPubKey,
            systemProgramId = systemProgramId,
            recentBlockhash = recentBlockhashBytes,
            instructionData = instructionData
        )
    }

    private fun buildNativeTransferTransactionMessage(
        senderPubKey: ByteArray,
        recipientPubKey: ByteArray,
        systemProgramId: ByteArray,
        recentBlockhash: ByteArray,
        instructionData: ByteArray
    ): ByteArray {
        val header = byteArrayOf(1, 0, 1)
        val accountKeys = senderPubKey + recipientPubKey + systemProgramId
        val numAccounts = byteArrayOf(3)
        val numInstructions = byteArrayOf(1)
        val programIdIndex = byteArrayOf(2)
        val numAccountIndices = byteArrayOf(2)
        val accountIndices = byteArrayOf(0, 1)
        val dataLen = compactU16(instructionData.size)

        return header +
            numAccounts +
            accountKeys +
            recentBlockhash +
            numInstructions +
            programIdIndex +
            numAccountIndices +
            accountIndices +
            dataLen +
            instructionData
    }

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
}
