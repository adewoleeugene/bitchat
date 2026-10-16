package com.bitchat.android.di

import com.bitchat.android.lending.LendingChannelService
import com.bitchat.android.lending.LendingCredibilityService
import com.bitchat.android.lending.LendingEscrowService
import com.bitchat.android.lending.LendingLoanService
import com.bitchat.android.solana.MessageNotarizationService
import com.bitchat.android.solana.NftAvatarService
import com.bitchat.android.solana.SolanaPaymentManager
import com.bitchat.android.solana.SolanaRpcService
import com.bitchat.android.solana.SolanaRelayHandler
import com.bitchat.android.solana.SolanaWalletService
import com.bitchat.android.solana.TokenGateService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for accessing Solana services from non-Hilt classes
 * (e.g., ChatViewModel which uses ViewModelProvider.Factory).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SolanaEntryPoint {
    fun solanaWalletService(): SolanaWalletService
    fun solanaPaymentManager(): SolanaPaymentManager
    fun solanaRpcService(): SolanaRpcService
    fun tokenGateService(): TokenGateService
    fun solanaRelayHandler(): SolanaRelayHandler
    fun messageNotarizationService(): MessageNotarizationService
    fun nftAvatarService(): NftAvatarService
    fun lendingChannelService(): LendingChannelService
    fun lendingCredibilityService(): LendingCredibilityService
    fun lendingLoanService(): LendingLoanService
    fun lendingEscrowService(): LendingEscrowService
}
