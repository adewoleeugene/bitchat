package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "loan_votes",
    primaryKeys = ["requestId", "voterPeerId"],
    indices = [
        Index(value = ["lendingId"]),
        Index(value = ["voteChoice"])
    ]
)
data class LoanVoteEntity(
    val requestId: String,
    val voterPeerId: String,
    val lendingId: String,
    val voteChoice: String,
    val votedAt: Long = System.currentTimeMillis()
)

object VoteChoice {
    const val YES = "YES"
}
