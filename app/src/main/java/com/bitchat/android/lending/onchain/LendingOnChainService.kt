package com.bitchat.android.lending.onchain

interface LendingOnChainService {
    fun isEnabled(): Boolean
    suspend fun initializeChannelOnChain(params: InitializeLendingChannelOnChainParams): Result<OnChainSubmissionResult>
    suspend fun createLoanRequestOnChain(params: CreateLoanRequestOnChainParams): Result<OnChainSubmissionResult>
    suspend fun castLoanVoteOnChain(params: CastLoanVoteOnChainParams): Result<OnChainSubmissionResult>
    suspend fun finalizeLoanRequestOnChain(params: FinalizeLoanRequestOnChainParams): Result<OnChainSubmissionResult>
    suspend fun recordLoanRepaymentOnChain(params: RecordLoanRepaymentOnChainParams): Result<OnChainSubmissionResult>
    suspend fun fetchLoanRequestState(lendingId: String, requestId: String): Result<OnChainLoanRequestState>
    suspend fun fetchVoteRecords(lendingId: String, requestId: String): Result<List<OnChainVoteRecord>>
}
