package com.bitchat.android.di

import com.bitchat.android.lending.LendingChannelService
import com.bitchat.android.lending.LendingChannelServiceImpl
import com.bitchat.android.lending.LendingCredibilityService
import com.bitchat.android.lending.LendingCredibilityServiceImpl
import com.bitchat.android.lending.LendingEscrowService
import com.bitchat.android.lending.LendingIdGenerator
import com.bitchat.android.lending.LendingLifecycleServiceImpl
import com.bitchat.android.lending.LendingLoanService
import com.bitchat.android.lending.SquadsService
import com.bitchat.android.lending.SquadsLendingEscrowServiceImpl
import com.bitchat.android.lending.SquadsServiceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LendingModule {

    @Provides
    @Singleton
    fun provideLendingIdGenerator(): LendingIdGenerator = LendingIdGenerator()

    @Provides
    @Singleton
    fun provideLendingChannelService(
        impl: LendingChannelServiceImpl
    ): LendingChannelService = impl

    @Provides
    @Singleton
    fun provideLendingCredibilityService(
        impl: LendingCredibilityServiceImpl
    ): LendingCredibilityService = impl

    @Provides
    @Singleton
    fun provideLendingLoanService(
        impl: LendingLifecycleServiceImpl
    ): LendingLoanService = impl

    @Provides
    @Singleton
    fun provideLendingEscrowService(
        impl: SquadsLendingEscrowServiceImpl
    ): LendingEscrowService = impl

    @Provides
    @Singleton
    fun provideSquadsService(
        impl: SquadsServiceImpl
    ): SquadsService = impl
}
