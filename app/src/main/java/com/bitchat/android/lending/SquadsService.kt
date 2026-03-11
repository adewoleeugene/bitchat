package com.bitchat.android.lending

data class SquadsConfig(
    val programId: String = DEFAULT_SQUADS_V4_PROGRAM_ID,
    val cluster: String = DEFAULT_SQUADS_CLUSTER,
    val requiredApprovalCount: Int = REQUIRED_LOAN_APPROVAL_COUNT,
    val targetMemberCount: Int = TARGET_LOAN_APPROVAL_MEMBER_COUNT
)

data class SquadsVaultAccount(
    val multisigAddress: String,
    val vaultAddress: String,
    val vaultTokenAccountAddress: String = "",
    val requiredApprovalCount: Int,
    val targetMemberCount: Int,
    val cluster: String
)

data class SquadsMultisigState(
    val multisigAddress: String,
    val threshold: Int,
    val transactionIndex: Long,
    val staleTransactionIndex: Long,
    val memberCount: Int
)

data class SquadsProposalState(
    val multisigAddress: String,
    val vaultAddress: String,
    val proposalAddress: String,
    val transactionIndex: Long,
    val approvedCount: Int,
    val threshold: Int,
    val status: String,
    val approvedAt: Long? = null,
    val executedAt: Long? = null,
    val txSignature: String? = null
)

interface SquadsService {
    fun config(): SquadsConfig
    suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount>
    suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState>
    suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState>
    suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState>
    suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState>
    suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?>
}

const val DEFAULT_SQUADS_V4_PROGRAM_ID = "SQDS4ep65T869zMMBKyuUq6aD6EgTu8psMjkvj52pCf"
const val DEFAULT_SQUADS_CLUSTER = "devnet"
const val SQUADS_PROPOSAL_STATUS_DRAFT = "DRAFT"
const val SQUADS_PROPOSAL_STATUS_ACTIVE = "ACTIVE"
const val SQUADS_PROPOSAL_STATUS_APPROVED = "APPROVED"
const val SQUADS_PROPOSAL_STATUS_EXECUTED = "EXECUTED"
const val SQUADS_PROPOSAL_STATUS_REJECTED = "REJECTED"
const val SQUADS_PROPOSAL_STATUS_CANCELLED = "CANCELLED"
