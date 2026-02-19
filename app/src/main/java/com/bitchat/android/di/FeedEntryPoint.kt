package com.bitchat.android.di

import com.bitchat.android.feed.FeedService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeedEntryPoint {
    fun feedService(): FeedService
}
