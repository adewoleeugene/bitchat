package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lending_pool_snapshots")
data class LendingPoolSnapshotEntity(
    @PrimaryKey
    val lendingId: String,
    val totalStakedAmount: Long = 0,
    val reservedAmount: Long = 0,
    val disbursedAmount: Long = 0,
    val availableLiquidityAmount: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
