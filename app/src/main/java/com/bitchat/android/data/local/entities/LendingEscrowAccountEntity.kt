package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lending_escrow_accounts")
data class LendingEscrowAccountEntity(
    @PrimaryKey
    val lendingId: String,
    val multisigAddress: String,
    val vaultAddress: String,
    val provider: String = EscrowProvider.SQUADS,
    val custodyState: String = EscrowCustodyState.PROVISIONED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object EscrowProvider {
    const val SQUADS = "SQUADS"
}

object EscrowCustodyState {
    const val PROVISIONED = "PROVISIONED"
    const val ACTIVE = "ACTIVE"
    const val FAILED = "FAILED"
}
