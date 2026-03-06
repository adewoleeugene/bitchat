package com.bitchat.android.solana

import javax.inject.Inject
import javax.inject.Singleton

interface LendingTransferGateway {
    suspend fun queueSplTransfer(
        recipientPublicKey: String,
        mintAddress: String,
        amountAtomic: Long,
        decimals: Int,
        symbol: String,
        memo: String? = null
    ): Result<String>
}

@Singleton
class PaymentManagerLendingTransferGateway @Inject constructor(
    private val paymentManager: SolanaPaymentManager
) : LendingTransferGateway {
    override suspend fun queueSplTransfer(
        recipientPublicKey: String,
        mintAddress: String,
        amountAtomic: Long,
        decimals: Int,
        symbol: String,
        memo: String?
    ): Result<String> {
        return paymentManager.queueSplTokenTransfer(
            recipientPublicKey = recipientPublicKey,
            mintAddress = mintAddress,
            amountAtomic = amountAtomic,
            decimals = decimals,
            symbol = symbol,
            memo = memo
        )
    }
}
