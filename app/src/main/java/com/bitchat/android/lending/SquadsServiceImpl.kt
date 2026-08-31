package com.bitchat.android.lending

import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.LoanChainStatus
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.solana.SolanaRpcService
import com.bitchat.android.solana.SolanaKeyDerivation
import com.bitchat.android.solana.SolanaTokenAccountUtils
import com.bitchat.android.solana.SolanaWalletService
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SquadsServiceImpl @Inject constructor(
    private val lendingDao: LendingDao,
    private val rpcService: SolanaRpcService,
    private val walletService: SolanaWalletService
) : SquadsService {
    private val squadsConfig = SquadsConfig()

    override fun config(): SquadsConfig = squadsConfig

    override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> {
        val escrow = lendingDao.getEscrowAccount(lendingId)
        val channel = lendingDao.getLendingChannelById(lendingId)
            ?: return Result.failure(IllegalArgumentException("lending_channel_not_found"))

        val multisigAddress = escrow?.multisigAddress?.takeIf { it.isNotBlank() }
            ?: channel.escrowMultisigAddress.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("squad_not_configured"))

        val vaultAddress = escrow?.vaultAddress?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("squad_vault_not_configured"))

        return Result.success(
            SquadsVaultAccount(
                multisigAddress = multisigAddress,
                vaultAddress = vaultAddress,
                vaultTokenAccountAddress = escrow.vaultTokenAccountAddress,
                requiredApprovalCount = squadsConfig.requiredApprovalCount,
                targetMemberCount = squadsConfig.targetMemberCount,
                cluster = squadsConfig.cluster
            )
        )
    }

    override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> {
        val account = rpcService.getAccountInfoBase64(multisigAddress).getOrElse { return Result.failure(it) }
        return runCatching { SquadsCodec.parseMultisigState(multisigAddress, account.dataBase64) }
    }

    override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> {
        val configAddress = SquadsCodec.getProgramConfigPda(squadsConfig.programId)
        val account = rpcService.getAccountInfoBase64(configAddress).getOrElse { return Result.failure(it) }
        return runCatching { SquadsCodec.parseProgramConfigState(account.dataBase64) }
    }

    override suspend fun createLendingMultisig(
        memberWallets: List<String>,
        threshold: Int
    ): Result<SquadsCreatedMultisig> {
        val creatorWallet = walletService.getPublicKeyBase58()
            ?: return Result.failure(IllegalStateException("wallet_not_initialized"))
        val uniqueMembers = buildList {
            add(creatorWallet)
            memberWallets
                .map { it.trim() }
                .filter { it.isNotBlank() && it != creatorWallet }
                .forEach { add(it) }
        }.distinct()
        if (uniqueMembers.size < squadsConfig.targetMemberCount) {
            return Result.failure(IllegalArgumentException("squad_member_count_must_be_at_least_${squadsConfig.targetMemberCount}"))
        }
        if (threshold != squadsConfig.requiredApprovalCount) {
            return Result.failure(IllegalArgumentException("squad_threshold_must_be_${squadsConfig.requiredApprovalCount}"))
        }

        val programConfig = fetchProgramConfigState().getOrElse { return Result.failure(it) }
        val createKeyPrivate = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val createKeyPublic = SolanaKeyDerivation.encodeBase58(SolanaKeyDerivation.derivePublicKey(createKeyPrivate))
        val multisigAddress = SquadsCodec.getMultisigPda(squadsConfig.programId, createKeyPublic)
        val vaultAddress = SquadsCodec.getVaultPda(squadsConfig.programId, multisigAddress, 0)
        val blockhash = rpcService.getLatestBlockhash().getOrElse { return Result.failure(it) }
        val signature = submitInstruction(
            recentBlockhash = blockhash.blockhash,
            signers = listOf(
                SquadsCodec.SignedMessageSigner(creatorWallet) { message -> walletService.sign(message) },
                SquadsCodec.SignedMessageSigner(createKeyPublic) { message ->
                    walletService.signWithPrivateKeyBytes(message, createKeyPrivate)
                }
            ),
            instructions = listOf(
                SquadsCodec.SquadsInstruction(
                    programId = squadsConfig.programId,
                    accounts = listOf(
                        SquadsCodec.SquadsAccountMeta(
                            SquadsCodec.getProgramConfigPda(squadsConfig.programId),
                            isSigner = false,
                            isWritable = false
                        ),
                        SquadsCodec.SquadsAccountMeta(createKeyPublic, isSigner = true, isWritable = false),
                        SquadsCodec.SquadsAccountMeta(creatorWallet, isSigner = true, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(multisigAddress, isSigner = false, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(SolanaTokenAccountUtils.SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
                        SquadsCodec.SquadsAccountMeta(programConfig.treasuryAddress, isSigner = false, isWritable = true)
                    ),
                    data = SquadsCodec.multisigCreateV2Data(
                        threshold = threshold,
                        members = uniqueMembers.map { memberWallet ->
                            SquadsCodec.SquadsMember(memberWallet)
                        }
                    )
                )
            )
        )
        return Result.success(
            SquadsCreatedMultisig(
                multisigAddress = multisigAddress,
                vaultAddress = vaultAddress,
                txSignature = signature,
                threshold = threshold,
                memberCount = uniqueMembers.size,
                cluster = squadsConfig.cluster
            )
        )
    }

    override suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
        val existing = fetchLoanProposalState(lendingId, requestId).getOrNull()
        if (existing != null) return Result.success(existing)

        val loan = lendingDao.getLoanRequestById(requestId)
            ?: return Result.failure(IllegalArgumentException("loan_request_not_found"))
        val channel = lendingDao.getLendingChannelById(lendingId)
            ?: return Result.failure(IllegalArgumentException("lending_channel_not_found"))

        val squad = resolveLendingSquad(lendingId).getOrElse { return Result.failure(it) }
        val multisig = fetchMultisigState(squad.multisigAddress).getOrElse { return Result.failure(it) }
        val memberWallet = walletService.getPublicKeyBase58()
            ?: return Result.failure(IllegalStateException("wallet_not_initialized"))
        val blockhash = rpcService.getLatestBlockhash().getOrElse { return Result.failure(it) }
        val nextTransactionIndex = multisig.transactionIndex + 1L
        val transactionPda = SquadsCodec.getTransactionPda(squadsConfig.programId, squad.multisigAddress, nextTransactionIndex)
        val proposalPda = SquadsCodec.getProposalPda(squadsConfig.programId, squad.multisigAddress, nextTransactionIndex)
        val targetWallet = resolveLoanRecipientWallet(loan, channel)
        val transactionMessage = if (isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol)) {
            SquadsCodec.legacyTransferInstructionMessage(
                vaultAddress = squad.vaultAddress,
                recipientAddress = targetWallet,
                amountLamports = loan.principalAmount
            )
        } else {
            val vaultTokenAccount = squad.vaultTokenAccountAddress?.takeIf { it.isNotBlank() }
                ?: SolanaTokenAccountUtils.findAssociatedTokenAddress(squad.vaultAddress, channel.stakeTokenMint)
            val recipientTokenAccount = SolanaTokenAccountUtils.findAssociatedTokenAddress(targetWallet, channel.stakeTokenMint)
            SquadsCodec.legacySplTransferInstructionMessage(
                vaultAddress = squad.vaultAddress,
                sourceTokenAccount = vaultTokenAccount,
                mintAddress = channel.stakeTokenMint,
                destinationTokenAccount = recipientTokenAccount,
                amountAtomic = loan.principalAmount,
                decimals = channel.stakeTokenDecimals
            )
        }

        val createTxSignature = submitInstruction(
            recentBlockhash = blockhash.blockhash,
            instructions = listOf(
                SquadsCodec.SquadsInstruction(
                    programId = squadsConfig.programId,
                    accounts = listOf(
                        SquadsCodec.SquadsAccountMeta(squad.multisigAddress, isSigner = false, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(transactionPda, isSigner = false, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(memberWallet, isSigner = true, isWritable = false),
                        SquadsCodec.SquadsAccountMeta(memberWallet, isSigner = true, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(SolanaTokenAccountUtils.SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false)
                    ),
                    data = SquadsCodec.vaultTransactionCreateData(
                        vaultIndex = 0,
                        ephemeralSigners = 0,
                        transactionMessage = transactionMessage
                    )
                ),
                SquadsCodec.SquadsInstruction(
                    programId = squadsConfig.programId,
                    accounts = listOf(
                        SquadsCodec.SquadsAccountMeta(squad.multisigAddress, isSigner = false, isWritable = false),
                        SquadsCodec.SquadsAccountMeta(proposalPda, isSigner = false, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(memberWallet, isSigner = true, isWritable = false),
                        SquadsCodec.SquadsAccountMeta(memberWallet, isSigner = true, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(SolanaTokenAccountUtils.SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false)
                    ),
                    data = SquadsCodec.proposalCreateData(nextTransactionIndex)
                )
            ),
            signerPublicKey = memberWallet
        )

        val updatedLoan = loan.copy(
            squadsMultisigAddress = squad.multisigAddress,
            squadsVaultAddress = squad.vaultAddress,
            squadsProposalAddress = proposalPda,
            squadsTransactionIndex = nextTransactionIndex,
            lastChainSyncSignature = createTxSignature,
            chainStatus = LoanChainStatus.SUBMITTED
        )
        lendingDao.upsertLoanRequest(updatedLoan)
        return fetchLoanProposalState(lendingId, requestId)
            .map { it?.copy(txSignature = createTxSignature) ?: throw IllegalStateException("squad_proposal_not_found") }
    }

    override suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
        val loan = lendingDao.getLoanRequestById(requestId)
            ?: return Result.failure(IllegalArgumentException("loan_request_not_found"))
        val squad = resolveLendingSquad(lendingId).getOrElse { return Result.failure(it) }
        val proposalAddress = loan.squadsProposalAddress
            ?: return Result.failure(IllegalStateException("squad_proposal_not_created"))
        val transactionIndex = loan.squadsTransactionIndex
            ?: return Result.failure(IllegalStateException("squad_transaction_index_missing"))
        val memberWallet = walletService.getPublicKeyBase58()
            ?: return Result.failure(IllegalStateException("wallet_not_initialized"))
        val blockhash = rpcService.getLatestBlockhash().getOrElse { return Result.failure(it) }
        val signature = submitInstruction(
            recentBlockhash = blockhash.blockhash,
            signerPublicKey = memberWallet,
            instructions = listOf(
                SquadsCodec.SquadsInstruction(
                    programId = squadsConfig.programId,
                    accounts = listOf(
                        SquadsCodec.SquadsAccountMeta(squad.multisigAddress, isSigner = false, isWritable = false),
                        SquadsCodec.SquadsAccountMeta(memberWallet, isSigner = true, isWritable = true),
                        SquadsCodec.SquadsAccountMeta(proposalAddress, isSigner = false, isWritable = true)
                    ),
                    data = SquadsCodec.proposalApproveData()
                )
            )
        )
        lendingDao.upsertLoanRequest(loan.copy(lastChainSyncSignature = signature, chainStatus = LoanChainStatus.SUBMITTED))
        return fetchLoanProposalState(lendingId, requestId)
            .map { it?.copy(txSignature = signature) ?: throw IllegalStateException("squad_proposal_not_found") }
    }

    override suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
        val loan = lendingDao.getLoanRequestById(requestId)
            ?: return Result.failure(IllegalArgumentException("loan_request_not_found"))
        val channel = lendingDao.getLendingChannelById(lendingId)
            ?: return Result.failure(IllegalArgumentException("lending_channel_not_found"))
        val squad = resolveLendingSquad(lendingId).getOrElse { return Result.failure(it) }
        val proposalAddress = loan.squadsProposalAddress
            ?: return Result.failure(IllegalStateException("squad_proposal_not_created"))
        val transactionIndex = loan.squadsTransactionIndex
            ?: return Result.failure(IllegalStateException("squad_transaction_index_missing"))
        val memberWallet = walletService.getPublicKeyBase58()
            ?: return Result.failure(IllegalStateException("wallet_not_initialized"))
        val blockhash = rpcService.getLatestBlockhash().getOrElse { return Result.failure(it) }
        val targetWallet = resolveLoanRecipientWallet(loan, channel)
        val transactionPda = SquadsCodec.getTransactionPda(squadsConfig.programId, squad.multisigAddress, transactionIndex)
        val isNativeSol = isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol)
        // Build execute accounts — SPL transfers need additional token accounts
        val executeAccounts = mutableListOf(
            SquadsCodec.SquadsAccountMeta(squad.multisigAddress, isSigner = false, isWritable = false),
            SquadsCodec.SquadsAccountMeta(proposalAddress, isSigner = false, isWritable = true),
            SquadsCodec.SquadsAccountMeta(transactionPda, isSigner = false, isWritable = false),
            SquadsCodec.SquadsAccountMeta(memberWallet, isSigner = true, isWritable = false),
            SquadsCodec.SquadsAccountMeta(squad.vaultAddress, isSigner = false, isWritable = true)
        )
        if (isNativeSol) {
            executeAccounts.add(SquadsCodec.SquadsAccountMeta(targetWallet, isSigner = false, isWritable = true))
            executeAccounts.add(SquadsCodec.SquadsAccountMeta(SolanaTokenAccountUtils.SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false))
        } else {
            val vaultTokenAccount = squad.vaultTokenAccountAddress?.takeIf { it.isNotBlank() }
                ?: SolanaTokenAccountUtils.findAssociatedTokenAddress(squad.vaultAddress, channel.stakeTokenMint)
            val recipientTokenAccount = SolanaTokenAccountUtils.findAssociatedTokenAddress(targetWallet, channel.stakeTokenMint)
            executeAccounts.add(SquadsCodec.SquadsAccountMeta(vaultTokenAccount, isSigner = false, isWritable = true))
            executeAccounts.add(SquadsCodec.SquadsAccountMeta(recipientTokenAccount, isSigner = false, isWritable = true))
            executeAccounts.add(SquadsCodec.SquadsAccountMeta(channel.stakeTokenMint, isSigner = false, isWritable = false))
            executeAccounts.add(SquadsCodec.SquadsAccountMeta(SolanaTokenAccountUtils.TOKEN_PROGRAM_ID, isSigner = false, isWritable = false))
        }
        val signature = submitInstruction(
            recentBlockhash = blockhash.blockhash,
            signerPublicKey = memberWallet,
            instructions = listOf(
                SquadsCodec.SquadsInstruction(
                    programId = squadsConfig.programId,
                    accounts = executeAccounts,
                    data = SquadsCodec.vaultTransactionExecuteData()
                )
            )
        )
        lendingDao.upsertLoanRequest(loan.copy(lastChainSyncSignature = signature, chainStatus = LoanChainStatus.SUBMITTED))
        return fetchLoanProposalState(lendingId, requestId)
            .map { it?.copy(txSignature = signature) ?: throw IllegalStateException("squad_proposal_not_found") }
    }

    override suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?> {
        val loan = lendingDao.getLoanRequestById(requestId)
            ?: return Result.failure(IllegalArgumentException("loan_request_not_found"))
        val proposalAddress = loan.squadsProposalAddress ?: return Result.success(null)
        val transactionIndex = loan.squadsTransactionIndex ?: return Result.success(null)
        val multisigAddress = loan.squadsMultisigAddress ?: return Result.success(null)
        val vaultAddress = loan.squadsVaultAddress ?: return Result.success(null)
        val multisig = fetchMultisigState(multisigAddress).getOrElse { return Result.failure(it) }
        val proposalAccount = rpcService.getAccountInfoBase64(proposalAddress).getOrElse { return Result.failure(it) }
        return runCatching {
            SquadsCodec.parseProposalState(
                multisigAddress = multisigAddress,
                vaultAddress = vaultAddress,
                proposalAddress = proposalAddress,
                threshold = multisig.threshold,
                transactionIndex = transactionIndex,
                dataBase64 = proposalAccount.dataBase64,
                txSignature = loan.lastChainSyncSignature
            )
        }
    }

    private suspend fun submitInstruction(
        recentBlockhash: String,
        signerPublicKey: String,
        instructions: List<SquadsCodec.SquadsInstruction>
    ): String {
        val signed = SquadsCodec.buildSignedLegacyTransaction(
            recentBlockhash = recentBlockhash,
            signerPublicKey = signerPublicKey,
            signer = { message -> walletService.sign(message) },
            instructions = instructions
        )
        return rpcService.sendTransaction(signed).getOrElse { throw it }
    }

    private suspend fun submitInstruction(
        recentBlockhash: String,
        signers: List<SquadsCodec.SignedMessageSigner>,
        instructions: List<SquadsCodec.SquadsInstruction>
    ): String {
        val signed = SquadsCodec.buildSignedLegacyTransaction(
            recentBlockhash = recentBlockhash,
            signers = signers,
            instructions = instructions
        )
        return rpcService.sendTransaction(signed).getOrElse { throw it }
    }

    private suspend fun resolveLoanRecipientWallet(
        loan: com.bitchat.android.data.local.entities.LoanRequestEntity,
        channel: com.bitchat.android.data.local.entities.LendingChannelEntity
    ): String {
        return if (loan.borrowerType == BorrowerType.INDIVIDUAL) {
            val borrowerWallet = loan.borrowerPeerId?.let { peerId ->
                lendingDao.getMembership(loan.lendingId, peerId)?.walletAddress
            }
            borrowerWallet ?: throw IllegalStateException("borrower_wallet_not_found")
        } else {
            channel.creatorWalletAddress
        }
    }
}
