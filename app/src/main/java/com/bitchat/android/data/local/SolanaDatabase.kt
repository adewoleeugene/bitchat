package com.bitchat.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bitchat.android.data.local.entities.FeedPostEntity
import com.bitchat.android.data.local.entities.FeedReactionEntity
import com.bitchat.android.data.local.entities.FeedReplyEntity
import com.bitchat.android.data.local.entities.MessageNotarizationEntity
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.local.entities.TokenGateConfigEntity
import com.bitchat.android.data.local.entities.TokenGateEligibilityCacheEntity
import com.bitchat.android.data.local.entities.WalletEntity

@Database(
    entities = [
        WalletEntity::class,
        QueuedTransactionEntity::class,
        TokenGateConfigEntity::class,
        TokenGateEligibilityCacheEntity::class,
        MessageNotarizationEntity::class,
        FeedPostEntity::class,
        FeedReactionEntity::class,
        FeedReplyEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class SolanaDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun transactionDao(): TransactionDao
    abstract fun tokenGateDao(): TokenGateDao
    abstract fun notarizationDao(): NotarizationDao
    abstract fun feedDao(): FeedDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `token_gate_configs` (
                        `channelKey` TEXT NOT NULL PRIMARY KEY,
                        `gateType` TEXT NOT NULL,
                        `tokenMintAddress` TEXT NOT NULL,
                        `minBalance` INTEGER NOT NULL,
                        `tokenSymbol` TEXT NOT NULL DEFAULT '',
                        `tokenDecimals` INTEGER NOT NULL DEFAULT 0,
                        `creatorPublicKey` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `lastValidatedAt` INTEGER NOT NULL DEFAULT 0,
                        `isUserEligible` INTEGER NOT NULL DEFAULT 0,
                        `validationTtlMs` INTEGER NOT NULL DEFAULT 86400000
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `message_notarizations` (
                        `messageId` TEXT NOT NULL PRIMARY KEY,
                        `messageHash` TEXT NOT NULL,
                        `senderNickname` TEXT NOT NULL,
                        `contentPreview` TEXT NOT NULL,
                        `messageTimestamp` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `txSignature` TEXT,
                        `slot` INTEGER,
                        `blockTime` INTEGER,
                        `errorMessage` TEXT,
                        `batchId` TEXT
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `feed_posts` (
                        `postId` TEXT NOT NULL PRIMARY KEY,
                        `authorPeerID` TEXT NOT NULL,
                        `authorNickname` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `hasImage` INTEGER NOT NULL DEFAULT 0,
                        `imagePath` TEXT,
                        `timestamp` INTEGER NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `reactionCount` INTEGER NOT NULL DEFAULT 0,
                        `replyCount` INTEGER NOT NULL DEFAULT 0,
                        `isOwnPost` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `feed_reactions` (
                        `postId` TEXT NOT NULL,
                        `reactorPeerID` TEXT NOT NULL,
                        `reactorNickname` TEXT NOT NULL,
                        `emoji` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`postId`, `reactorPeerID`, `emoji`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `feed_replies` (
                        `replyId` TEXT NOT NULL PRIMARY KEY,
                        `parentPostId` TEXT NOT NULL,
                        `authorPeerID` TEXT NOT NULL,
                        `authorNickname` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `receivedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    ALTER TABLE `token_gate_configs`
                    ADD COLUMN `policyVersion` INTEGER NOT NULL DEFAULT 1
                """.trimIndent())
                db.execSQL("""
                    ALTER TABLE `token_gate_configs`
                    ADD COLUMN `gateHash` TEXT NOT NULL DEFAULT ''
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `token_gate_eligibility_cache` (
                        `channelKey` TEXT NOT NULL,
                        `walletAddress` TEXT NOT NULL,
                        `gateHash` TEXT NOT NULL,
                        `isEligible` INTEGER NOT NULL,
                        `observedBalance` INTEGER NOT NULL,
                        `validatedAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `rpcSlot` INTEGER,
                        `errorCode` TEXT,
                        PRIMARY KEY(`channelKey`, `walletAddress`, `gateHash`)
                    )
                """.trimIndent())
            }
        }
    }
}
