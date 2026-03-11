package com.bitchat.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bitchat.android.data.local.entities.FeedPostEntity
import com.bitchat.android.data.local.entities.FeedReactionEntity
import com.bitchat.android.data.local.entities.FeedReplyEntity
import com.bitchat.android.data.local.entities.CredibilityProfileEntity
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingEscrowAccountEntity
import com.bitchat.android.data.local.entities.LendingEscrowProposalEntity
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LoanRepaymentEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.data.local.entities.LoanVoteEntity
import com.bitchat.android.data.local.entities.MessageNotarizationEntity
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.local.entities.TokenGateConfigEntity
import com.bitchat.android.data.local.entities.TokenGateEligibilityCacheEntity
import com.bitchat.android.data.local.entities.TokenGatePolicyStateEntity
import com.bitchat.android.data.local.entities.WalletEntity

@Database(
    entities = [
        WalletEntity::class,
        QueuedTransactionEntity::class,
        TokenGateConfigEntity::class,
        TokenGateEligibilityCacheEntity::class,
        TokenGatePolicyStateEntity::class,
        MessageNotarizationEntity::class,
        FeedPostEntity::class,
        FeedReactionEntity::class,
        FeedReplyEntity::class,
        LendingChannelEntity::class,
        LendingEscrowAccountEntity::class,
        LendingEscrowProposalEntity::class,
        LendingMembershipEntity::class,
        LendingPoolSnapshotEntity::class,
        LoanRequestEntity::class,
        LoanVoteEntity::class,
        LoanRepaymentEntity::class,
        CredibilityProfileEntity::class
    ],
    version = 19,
    exportSchema = false
)
abstract class SolanaDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun transactionDao(): TransactionDao
    abstract fun tokenGateDao(): TokenGateDao
    abstract fun notarizationDao(): NotarizationDao
    abstract fun feedDao(): FeedDao
    abstract fun lendingDao(): LendingDao

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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `token_gate_policy_state` (
                        `channelKey` TEXT NOT NULL PRIMARY KEY,
                        `creatorPublicKey` TEXT NOT NULL,
                        `lastPolicyVersion` INTEGER NOT NULL,
                        `lastGateHash` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isRemoved` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `token_gate_policy_state`
                    (`channelKey`, `creatorPublicKey`, `lastPolicyVersion`, `lastGateHash`, `updatedAt`, `isRemoved`)
                    SELECT
                        `channelKey`,
                        `creatorPublicKey`,
                        `policyVersion`,
                        `gateHash`,
                        `createdAt`,
                        0
                    FROM `token_gate_configs`
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `feed_posts`
                    ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `feed_posts`
                    ADD COLUMN `pinnedAt` INTEGER
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `feed_posts`
                    ADD COLUMN `pinnedByPeerID` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `feed_posts`
                    ADD COLUMN `pinVersion` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `feed_posts`
                    ADD COLUMN `channelKey` TEXT NOT NULL DEFAULT 'mesh:#mesh'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_feed_posts_channelKey_timestamp`
                    ON `feed_posts` (`channelKey`, `timestamp`)
                    """.trimIndent()
                )
            }
        }

        /**
         * Normalize feed_posts schema to match the Room entity definition:
         * - channelKey must NOT carry a DB-level default value.
         * - no explicit index on (channelKey, timestamp) in current schema.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `feed_posts_new` (
                        `postId` TEXT NOT NULL PRIMARY KEY,
                        `channelKey` TEXT NOT NULL,
                        `authorPeerID` TEXT NOT NULL,
                        `authorNickname` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `hasImage` INTEGER NOT NULL,
                        `imagePath` TEXT,
                        `timestamp` INTEGER NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `reactionCount` INTEGER NOT NULL,
                        `replyCount` INTEGER NOT NULL,
                        `isOwnPost` INTEGER NOT NULL,
                        `isPinned` INTEGER NOT NULL,
                        `pinnedAt` INTEGER,
                        `pinnedByPeerID` TEXT,
                        `pinVersion` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `feed_posts_new` (
                        `postId`,
                        `channelKey`,
                        `authorPeerID`,
                        `authorNickname`,
                        `content`,
                        `hasImage`,
                        `imagePath`,
                        `timestamp`,
                        `receivedAt`,
                        `reactionCount`,
                        `replyCount`,
                        `isOwnPost`,
                        `isPinned`,
                        `pinnedAt`,
                        `pinnedByPeerID`,
                        `pinVersion`
                    )
                    SELECT
                        `postId`,
                        `channelKey`,
                        `authorPeerID`,
                        `authorNickname`,
                        `content`,
                        `hasImage`,
                        `imagePath`,
                        `timestamp`,
                        `receivedAt`,
                        `reactionCount`,
                        `replyCount`,
                        `isOwnPost`,
                        `isPinned`,
                        `pinnedAt`,
                        `pinnedByPeerID`,
                        `pinVersion`
                    FROM `feed_posts`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `feed_posts`")
                db.execSQL("ALTER TABLE `feed_posts_new` RENAME TO `feed_posts`")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `feed_posts`
                    ADD COLUMN `hasAudio` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `feed_posts`
                    ADD COLUMN `audioPath` TEXT
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lending_channels` (
                        `lendingId` TEXT NOT NULL,
                        `channelKey` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `creatorPeerId` TEXT NOT NULL,
                        `creatorWalletAddress` TEXT NOT NULL,
                        `requiredStakeAmount` INTEGER NOT NULL,
                        `stakeTokenMint` TEXT NOT NULL,
                        `stakeTokenSymbol` TEXT NOT NULL,
                        `stakeTokenDecimals` INTEGER NOT NULL,
                        `escrowMultisigAddress` TEXT NOT NULL,
                        `quorumThresholdPercent` INTEGER NOT NULL,
                        `approvalThresholdPercent` INTEGER NOT NULL,
                        `votingWindowHours` INTEGER NOT NULL,
                        `lifecycleState` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`lendingId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_lending_channels_channelKey`
                    ON `lending_channels` (`channelKey`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lending_memberships` (
                        `lendingId` TEXT NOT NULL,
                        `memberPeerId` TEXT NOT NULL,
                        `walletAddress` TEXT NOT NULL,
                        `stakeAmount` INTEGER NOT NULL,
                        `depositStatus` TEXT NOT NULL,
                        `joinStatus` TEXT NOT NULL,
                        `credibilityScore` INTEGER NOT NULL,
                        `credibilitySnapshotJson` TEXT NOT NULL,
                        `joinedAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`lendingId`, `memberPeerId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_lending_memberships_walletAddress`
                    ON `lending_memberships` (`walletAddress`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_lending_memberships_joinStatus`
                    ON `lending_memberships` (`joinStatus`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_lending_memberships_depositStatus`
                    ON `lending_memberships` (`depositStatus`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lending_pool_snapshots` (
                        `lendingId` TEXT NOT NULL,
                        `totalStakedAmount` INTEGER NOT NULL,
                        `reservedAmount` INTEGER NOT NULL,
                        `disbursedAmount` INTEGER NOT NULL,
                        `availableLiquidityAmount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`lendingId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `loan_requests` (
                        `requestId` TEXT NOT NULL,
                        `lendingId` TEXT NOT NULL,
                        `borrowerType` TEXT NOT NULL,
                        `borrowerPeerId` TEXT,
                        `borrowerGroupKey` TEXT,
                        `principalAmount` INTEGER NOT NULL,
                        `interestBps` INTEGER NOT NULL,
                        `durationDays` INTEGER NOT NULL,
                        `purpose` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `requestedAt` INTEGER NOT NULL,
                        `dueAt` INTEGER NOT NULL,
                        `approvedAt` INTEGER,
                        `disbursedAt` INTEGER,
                        `defaultedAt` INTEGER,
                        PRIMARY KEY(`requestId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_requests_lendingId`
                    ON `loan_requests` (`lendingId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_requests_status`
                    ON `loan_requests` (`status`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_requests_borrowerPeerId`
                    ON `loan_requests` (`borrowerPeerId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `loan_votes` (
                        `requestId` TEXT NOT NULL,
                        `voterPeerId` TEXT NOT NULL,
                        `lendingId` TEXT NOT NULL,
                        `voteChoice` TEXT NOT NULL,
                        `votedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`requestId`, `voterPeerId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_votes_lendingId`
                    ON `loan_votes` (`lendingId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_votes_voteChoice`
                    ON `loan_votes` (`voteChoice`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `loan_repayments` (
                        `repaymentId` TEXT NOT NULL,
                        `requestId` TEXT NOT NULL,
                        `lendingId` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `txSignature` TEXT,
                        `txStatus` TEXT NOT NULL,
                        `paidAt` INTEGER NOT NULL,
                        PRIMARY KEY(`repaymentId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_repayments_requestId`
                    ON `loan_repayments` (`requestId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_repayments_lendingId`
                    ON `loan_repayments` (`lendingId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `credibility_profiles` (
                        `profileId` TEXT NOT NULL,
                        `subjectType` TEXT NOT NULL,
                        `subjectKey` TEXT NOT NULL,
                        `score` INTEGER NOT NULL,
                        `usageAgePoints` INTEGER NOT NULL,
                        `participationPoints` INTEGER NOT NULL,
                        `recentActivityPoints` INTEGER NOT NULL,
                        `walletStrengthPoints` INTEGER NOT NULL,
                        `hardGateStatus` TEXT NOT NULL,
                        `firstSeenAt` INTEGER NOT NULL,
                        `lastComputedAt` INTEGER NOT NULL,
                        `snapshotJson` TEXT NOT NULL,
                        PRIMARY KEY(`profileId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_credibility_profiles_subjectType_subjectKey`
                    ON `credibility_profiles` (`subjectType`, `subjectKey`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `queued_transactions`
                    ADD COLUMN `assetKind` TEXT NOT NULL DEFAULT 'NATIVE_SOL'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `queued_transactions`
                    ADD COLUMN `assetMintAddress` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `queued_transactions`
                    ADD COLUMN `assetSymbol` TEXT NOT NULL DEFAULT 'SOL'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `queued_transactions`
                    ADD COLUMN `assetDecimals` INTEGER NOT NULL DEFAULT 9
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lending_escrow_accounts` (
                        `lendingId` TEXT NOT NULL,
                        `multisigAddress` TEXT NOT NULL,
                        `vaultAddress` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `custodyState` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`lendingId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lending_escrow_proposals` (
                        `proposalId` TEXT NOT NULL,
                        `lendingId` TEXT NOT NULL,
                        `requestId` TEXT,
                        `memberPeerId` TEXT,
                        `proposalType` TEXT NOT NULL,
                        `appApprovalStatus` TEXT NOT NULL,
                        `custodyExecutionStatus` TEXT NOT NULL,
                        `targetWalletAddress` TEXT NOT NULL,
                        `mintAddress` TEXT NOT NULL,
                        `amountAtomic` INTEGER NOT NULL,
                        `txSignature` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`proposalId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_lending_escrow_proposals_lendingId`
                    ON `lending_escrow_proposals` (`lendingId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_lending_escrow_proposals_requestId`
                    ON `lending_escrow_proposals` (`requestId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_lending_escrow_proposals_proposalType`
                    ON `lending_escrow_proposals` (`proposalType`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_lending_escrow_proposals_custodyExecutionStatus`
                    ON `lending_escrow_proposals` (`custodyExecutionStatus`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `lending_escrow_accounts`
                    ADD COLUMN `vaultTokenAccountAddress` TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `lending_escrow_accounts`
                    ADD COLUMN `treasuryContactsJson` TEXT NOT NULL DEFAULT '[]'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `lending_escrow_accounts`
                    ADD COLUMN `pendingMigrationMultisigAddress` TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `loan_requests_new` (
                        `requestId` TEXT NOT NULL PRIMARY KEY,
                        `lendingId` TEXT NOT NULL,
                        `borrowerType` TEXT NOT NULL,
                        `borrowerPeerId` TEXT,
                        `borrowerGroupKey` TEXT,
                        `principalAmount` INTEGER NOT NULL,
                        `interestBps` INTEGER NOT NULL,
                        `durationDays` INTEGER NOT NULL,
                        `purpose` TEXT NOT NULL,
                        `endorserPeerIdsJson` TEXT NOT NULL DEFAULT '[]',
                        `status` TEXT NOT NULL,
                        `requestedAt` INTEGER NOT NULL,
                        `dueAt` INTEGER NOT NULL,
                        `approvedAt` INTEGER,
                        `disbursedAt` INTEGER,
                        `defaultedAt` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `loan_requests_new`
                    (`requestId`, `lendingId`, `borrowerType`, `borrowerPeerId`, `borrowerGroupKey`, `principalAmount`, `interestBps`, `durationDays`, `purpose`, `endorserPeerIdsJson`, `status`, `requestedAt`, `dueAt`, `approvedAt`, `disbursedAt`, `defaultedAt`)
                    SELECT
                        `requestId`, `lendingId`, `borrowerType`, `borrowerPeerId`, `borrowerGroupKey`, `principalAmount`, `interestBps`, `durationDays`, `purpose`, '[]', `status`, `requestedAt`, `dueAt`, `approvedAt`, `disbursedAt`, `defaultedAt`
                    FROM `loan_requests`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `loan_requests`")
                db.execSQL("ALTER TABLE `loan_requests_new` RENAME TO `loan_requests`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_lendingId` ON `loan_requests` (`lendingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_status` ON `loan_requests` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_borrowerPeerId` ON `loan_requests` (`borrowerPeerId`)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `channelPda` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `loanRequestPda` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `lastChainSyncSignature` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `lastChainSyncedSlot` INTEGER
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `chainStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_requests_loanRequestPda`
                    ON `loan_requests` (`loanRequestPda`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `squadsMultisigAddress` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `squadsVaultAddress` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `squadsProposalAddress` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `squadsTransactionIndex` INTEGER
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_requests_squadsProposalAddress`
                    ON `loan_requests` (`squadsProposalAddress`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `parentRequestId` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `requestKind` TEXT NOT NULL DEFAULT 'ORIGIN'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `originLendingId` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `forwardedFromRequestId` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `fundingLendingId` TEXT
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_requests_parentRequestId`
                    ON `loan_requests` (`parentRequestId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_requests_originLendingId`
                    ON `loan_requests` (`originLendingId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_loan_requests_fundingLendingId`
                    ON `loan_requests` (`fundingLendingId`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `loan_requests_new` (
                        `requestId` TEXT NOT NULL PRIMARY KEY,
                        `lendingId` TEXT NOT NULL,
                        `borrowerType` TEXT NOT NULL,
                        `borrowerPeerId` TEXT,
                        `borrowerGroupKey` TEXT,
                        `principalAmount` INTEGER NOT NULL,
                        `interestBps` INTEGER NOT NULL,
                        `durationDays` INTEGER NOT NULL,
                        `purpose` TEXT NOT NULL,
                        `endorserPeerIdsJson` TEXT NOT NULL DEFAULT '[]',
                        `status` TEXT NOT NULL,
                        `requestedAt` INTEGER NOT NULL,
                        `dueAt` INTEGER NOT NULL,
                        `approvedAt` INTEGER,
                        `disbursedAt` INTEGER,
                        `defaultedAt` INTEGER,
                        `parentRequestId` TEXT,
                        `requestKind` TEXT NOT NULL DEFAULT 'ORIGIN',
                        `originLendingId` TEXT,
                        `forwardedFromRequestId` TEXT,
                        `fundingLendingId` TEXT,
                        `squadsMultisigAddress` TEXT,
                        `squadsVaultAddress` TEXT,
                        `squadsProposalAddress` TEXT,
                        `squadsTransactionIndex` INTEGER,
                        `channelPda` TEXT,
                        `loanRequestPda` TEXT,
                        `lastChainSyncSignature` TEXT,
                        `lastChainSyncedSlot` INTEGER,
                        `chainStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `loan_requests_new` (
                        `requestId`,
                        `lendingId`,
                        `borrowerType`,
                        `borrowerPeerId`,
                        `borrowerGroupKey`,
                        `principalAmount`,
                        `interestBps`,
                        `durationDays`,
                        `purpose`,
                        `endorserPeerIdsJson`,
                        `status`,
                        `requestedAt`,
                        `dueAt`,
                        `approvedAt`,
                        `disbursedAt`,
                        `defaultedAt`,
                        `parentRequestId`,
                        `requestKind`,
                        `originLendingId`,
                        `forwardedFromRequestId`,
                        `fundingLendingId`,
                        `squadsMultisigAddress`,
                        `squadsVaultAddress`,
                        `squadsProposalAddress`,
                        `squadsTransactionIndex`,
                        `channelPda`,
                        `loanRequestPda`,
                        `lastChainSyncSignature`,
                        `lastChainSyncedSlot`,
                        `chainStatus`
                    )
                    SELECT
                        `requestId`,
                        `lendingId`,
                        `borrowerType`,
                        `borrowerPeerId`,
                        `borrowerGroupKey`,
                        `principalAmount`,
                        `interestBps`,
                        `durationDays`,
                        `purpose`,
                        COALESCE(`endorserPeerIdsJson`, '[]'),
                        `status`,
                        `requestedAt`,
                        `dueAt`,
                        `approvedAt`,
                        `disbursedAt`,
                        `defaultedAt`,
                        `parentRequestId`,
                        COALESCE(`requestKind`, 'ORIGIN'),
                        `originLendingId`,
                        `forwardedFromRequestId`,
                        `fundingLendingId`,
                        `squadsMultisigAddress`,
                        `squadsVaultAddress`,
                        `squadsProposalAddress`,
                        `squadsTransactionIndex`,
                        `channelPda`,
                        `loanRequestPda`,
                        `lastChainSyncSignature`,
                        `lastChainSyncedSlot`,
                        COALESCE(`chainStatus`, 'LOCAL_ONLY')
                    FROM `loan_requests`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `loan_requests`")
                db.execSQL("ALTER TABLE `loan_requests_new` RENAME TO `loan_requests`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_lendingId` ON `loan_requests` (`lendingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_status` ON `loan_requests` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_borrowerPeerId` ON `loan_requests` (`borrowerPeerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_parentRequestId` ON `loan_requests` (`parentRequestId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_originLendingId` ON `loan_requests` (`originLendingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_fundingLendingId` ON `loan_requests` (`fundingLendingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_loanRequestPda` ON `loan_requests` (`loanRequestPda`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_requests_squadsProposalAddress` ON `loan_requests` (`squadsProposalAddress`)")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `loan_requests`
                    ADD COLUMN `borrowerWalletAddress` TEXT
                    """.trimIndent()
                )
            }
        }
    }
}
