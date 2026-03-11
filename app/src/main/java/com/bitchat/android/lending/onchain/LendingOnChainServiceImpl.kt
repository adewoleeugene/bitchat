package com.bitchat.android.lending.onchain

import com.bitchat.android.solana.SolanaRpcService
import com.bitchat.android.solana.SolanaWalletService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingOnChainServiceImpl @Inject constructor(
    private val rpcService: SolanaRpcService,
    private val walletService: SolanaWalletService,
    private val transactionBuilder: LendingOnChainTransactionBuilder,
    private val config: LendingProgramConfig
) : LendingOnChainService {

    companion object {
        private const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
    }

    override fun isEnabled(): Boolean = config.enabled

    override suspend fun initializeChannelOnChain(params: InitializeLendingChannelOnChainParams): Result<OnChainSubmissionResult> {
        return submitChannelInstruction(
            lendingId = params.lendingId,
            data = LendingOnChainCodec.initializeChannelData(params),
            includeSystemProgram = true
        )
    }

    override suspend fun createLoanRequestOnChain(params: CreateLoanRequestOnChainParams): Result<OnChainSubmissionResult> {
        return submitLoanInstruction(
            lendingId = params.lendingId,
            requestId = params.requestId,
            data = LendingOnChainCodec.createLoanRequestData(params),
            includeSystemProgram = true
        )
    }

    override suspend fun castLoanVoteOnChain(params: CastLoanVoteOnChainParams): Result<OnChainSubmissionResult> {
        val wallet = walletService.getPublicKeyBase58()
            ?: return Result.failure(IllegalStateException("wallet_not_found"))
        val voteRecord = deriveVoteRecord(params.lendingId, params.requestId, wallet)
        return submitLoanInstruction(
            lendingId = params.lendingId,
            requestId = params.requestId,
            data = LendingOnChainCodec.castVoteData(params),
            voteRecordPda = voteRecord.address,
            includeSystemProgram = true
        )
    }

    override suspend fun finalizeLoanRequestOnChain(params: FinalizeLoanRequestOnChainParams): Result<OnChainSubmissionResult> {
        return submitLoanInstruction(
            lendingId = params.lendingId,
            requestId = params.requestId,
            data = LendingOnChainCodec.finalizeLoanRequestData(params)
        )
    }

    override suspend fun recordLoanRepaymentOnChain(params: RecordLoanRepaymentOnChainParams): Result<OnChainSubmissionResult> {
        return submitLoanInstruction(
            lendingId = params.lendingId,
            requestId = params.requestId,
            data = LendingOnChainCodec.recordRepaymentData(params)
        )
    }

    override suspend fun fetchLoanRequestState(lendingId: String, requestId: String): Result<OnChainLoanRequestState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val channelPda = LendingOnChainPda.findChannelPda(config.programId, lendingId).address
                val loanPda = LendingOnChainPda.findLoanRequestPda(config.programId, channelPda, requestId).address
                val info = rpcService.getAccountInfoBase64(loanPda).getOrThrow()
                LendingOnChainCodec.decodeLoanRequestState(
                    channelPda = channelPda,
                    loanRequestPda = loanPda,
                    dataBase64 = info.dataBase64,
                    slot = info.slot
                )
            }
        }
    }

    override suspend fun fetchVoteRecords(lendingId: String, requestId: String): Result<List<OnChainVoteRecord>> {
        return Result.success(emptyList())
    }

    private suspend fun submitChannelInstruction(
        lendingId: String,
        data: ByteArray,
        includeSystemProgram: Boolean
    ): Result<OnChainSubmissionResult> = withContext(Dispatchers.IO) {
        if (!config.enabled) {
            return@withContext Result.failure(IllegalStateException("lending_program_not_enabled"))
        }
        val signer = walletService.getPublicKeyBase58()
            ?: return@withContext Result.failure(IllegalStateException("wallet_not_found"))
        val channelPda = LendingOnChainPda.findChannelPda(config.programId, lendingId)
        val recentBlockhash = rpcService.getLatestBlockhash().getOrElse { return@withContext Result.failure(it) }
        val instruction = ProgramInstruction(
            programId = config.programId,
            accounts = buildList {
                add(AccountMeta(signer, isSigner = true, isWritable = true))
                add(AccountMeta(channelPda.address, isSigner = false, isWritable = true))
                if (includeSystemProgram) {
                    add(AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false))
                }
            },
            data = data
        )
        val signedTransaction = transactionBuilder.signLegacyTransaction(
            signerPublicKey = signer,
            recentBlockhash = recentBlockhash.blockhash,
            instructions = listOf(instruction)
        ) ?: return@withContext Result.failure(IllegalStateException("failed_to_sign_instruction"))
        val signature = rpcService.sendTransaction(signedTransaction).getOrElse { return@withContext Result.failure(it) }
        Result.success(
            OnChainSubmissionResult(
                channelPda = channelPda.address,
                txSignature = signature
            )
        )
    }

    private suspend fun submitLoanInstruction(
        lendingId: String,
        requestId: String,
        data: ByteArray,
        voteRecordPda: String? = null,
        includeSystemProgram: Boolean = false
    ): Result<OnChainSubmissionResult> = withContext(Dispatchers.IO) {
        if (!config.enabled) {
            return@withContext Result.failure(IllegalStateException("lending_program_not_enabled"))
        }
        val signer = walletService.getPublicKeyBase58()
            ?: return@withContext Result.failure(IllegalStateException("wallet_not_found"))
        val channelPda = LendingOnChainPda.findChannelPda(config.programId, lendingId)
        val loanRequestPda = LendingOnChainPda.findLoanRequestPda(config.programId, channelPda.address, requestId)
        val recentBlockhash = rpcService.getLatestBlockhash().getOrElse { return@withContext Result.failure(it) }
        val instruction = ProgramInstruction(
            programId = config.programId,
            accounts = buildList {
                add(AccountMeta(signer, isSigner = true, isWritable = true))
                add(AccountMeta(channelPda.address, isSigner = false, isWritable = true))
                add(AccountMeta(loanRequestPda.address, isSigner = false, isWritable = true))
                voteRecordPda?.let { add(AccountMeta(it, isSigner = false, isWritable = true)) }
                if (includeSystemProgram) {
                    add(AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false))
                }
            },
            data = data
        )
        val signedTransaction = transactionBuilder.signLegacyTransaction(
            signerPublicKey = signer,
            recentBlockhash = recentBlockhash.blockhash,
            instructions = listOf(instruction)
        ) ?: return@withContext Result.failure(IllegalStateException("failed_to_sign_instruction"))
        val signature = rpcService.sendTransaction(signedTransaction).getOrElse { return@withContext Result.failure(it) }
        Result.success(
            OnChainSubmissionResult(
                channelPda = channelPda.address,
                loanRequestPda = loanRequestPda.address,
                voteRecordPda = voteRecordPda,
                txSignature = signature
            )
        )
    }

    private fun deriveVoteRecord(lendingId: String, requestId: String, voterWallet: String): DerivedAddress {
        val channelPda = LendingOnChainPda.findChannelPda(config.programId, lendingId)
        val loanPda = LendingOnChainPda.findLoanRequestPda(config.programId, channelPda.address, requestId)
        return LendingOnChainPda.findVoteRecordPda(config.programId, loanPda.address, voterWallet)
    }
}
