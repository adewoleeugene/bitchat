package com.bitchat.android.lending

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.data.local.SolanaDatabase
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingMemberStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class LendingChannelServiceIntegrationTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var database: SolanaDatabase
    private lateinit var service: LendingChannelServiceImpl
    private lateinit var fakeSquadsService: FakeSquadsService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "solana_lending_service_${System.currentTimeMillis()}.db"
        database = Room.databaseBuilder(context, SolanaDatabase::class.java, dbName)
            .addMigrations(
                SolanaDatabase.MIGRATION_1_2,
                SolanaDatabase.MIGRATION_2_3,
                SolanaDatabase.MIGRATION_3_4,
                SolanaDatabase.MIGRATION_4_5,
                SolanaDatabase.MIGRATION_5_6,
                SolanaDatabase.MIGRATION_6_7,
                SolanaDatabase.MIGRATION_7_8,
                SolanaDatabase.MIGRATION_8_9,
                SolanaDatabase.MIGRATION_9_10,
                SolanaDatabase.MIGRATION_10_11,
                SolanaDatabase.MIGRATION_11_12,
                SolanaDatabase.MIGRATION_12_13,
                SolanaDatabase.MIGRATION_13_14,
                SolanaDatabase.MIGRATION_14_15,
                SolanaDatabase.MIGRATION_15_16,
                SolanaDatabase.MIGRATION_16_17,
                SolanaDatabase.MIGRATION_17_18,
                SolanaDatabase.MIGRATION_18_19,
                SolanaDatabase.MIGRATION_19_20,
                SolanaDatabase.MIGRATION_20_21,
                SolanaDatabase.MIGRATION_21_22
            )
            .build()
        database.openHelper.writableDatabase
        fakeSquadsService = FakeSquadsService()
        service = LendingChannelServiceImpl(
            lendingDao = database.lendingDao(),
            lendingIdGenerator = LendingIdGenerator(
                object : SecureRandom() {
                    private val values = intArrayOf(8, 9, 10, 11, 12, 13)
                    private var index = 0
                    override fun nextInt(bound: Int): Int {
                        val value = values[index % values.size]
                        index += 1
                        return value % bound
                    }
                }
            ),
            squadsService = fakeSquadsService
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun createLocalChannel_generatesLendingIdAndResolvesByIdentifier() = kotlinx.coroutines.runBlocking {
        val created = service.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#villagefund",
                displayName = "#villagefund",
                creatorPeerId = "peer-1",
                creatorWalletAddress = "Wallet111111111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                minimumVoteCount = 3,
                stakeTokenMint = "USDC",
                stakeTokenSymbol = "USDC"
            )
        )

        val byId = service.getChannelByIdentifier(created.lendingId)
        val byChannel = service.getChannelByIdentifier("#villagefund", "mesh:#villagefund")
        val status = service.getStatus(created.lendingId)

        assertEquals(6, created.lendingId.length)
        assertTrue(created.lendingId.all { it in "23456789ABCDEFGHJKLMNPQRSTVWXYZ" })
        assertNotNull(byId)
        assertNotNull(byChannel)
        assertNotNull(status)
        assertEquals(created.lendingId, byId!!.lendingId)
        assertEquals(created.lendingId, byChannel!!.lendingId)
        assertEquals(created.lendingId, status!!.channel.lendingId)
        assertEquals(3, created.minimumVoteCount)
    }

    @Test
    fun importSharedChannel_doesNotSeedCreatorLiquidityByDefault() = kotlinx.coroutines.runBlocking {
        val imported = service.importSharedChannel(
            ImportLendingChannelRequest(
                lendingId = "AB23CD",
                channelKey = "mesh:#importedfund",
                displayName = "#importedfund",
                creatorPeerId = "peer-creator",
                creatorWalletAddress = "Wallet111111111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                minimumVoteCount = 2,
                stakeTokenMint = "USDC",
                stakeTokenSymbol = "USDC"
            )
        )

        val status = service.getStatus(imported.lendingId)
        val creatorMembership = service.getMemberships(imported.lendingId)
            .firstOrNull { it.memberPeerId == "peer-creator" }

        assertNotNull(status)
        assertEquals(0L, status!!.poolSnapshot!!.totalStakedAmount)
        assertEquals(0L, status.poolSnapshot!!.availableLiquidityAmount)
        assertNull(creatorMembership)
    }

    @Test
    fun importMembershipUpdate_doesNotTrustRemoteConfirmedState() = kotlinx.coroutines.runBlocking {
        val created = service.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#pendingonly",
                displayName = "#pendingonly",
                creatorPeerId = "peer-1",
                creatorWalletAddress = "Wallet111111111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                minimumVoteCount = 2,
                stakeTokenMint = "USDC",
                stakeTokenSymbol = "USDC"
            )
        )

        val importedMembership = service.importMembershipUpdate(
            LendingMembershipMessage(
                lendingId = created.lendingId,
                memberPeerId = "peer-2",
                walletAddress = "Wallet222222222222222222222222222222222",
                stakeAmount = 50_000_000L,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            ),
            senderPeerId = "peer-2"
        )

        assertNotNull(importedMembership)
        assertEquals(EscrowTransferStatus.PENDING, importedMembership!!.depositStatus)
        assertEquals(LendingMemberStatus.PENDING, importedMembership.joinStatus)
    }

    @Test
    fun configureSquad_requiresChannelOwner() = kotlinx.coroutines.runBlocking {
        val created = service.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#owneronly",
                displayName = "#owneronly",
                creatorPeerId = "peer-owner",
                creatorWalletAddress = "Wallet111111111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                minimumVoteCount = 2,
                stakeTokenMint = "USDC",
                stakeTokenSymbol = "USDC"
            )
        )

        try {
            service.configureSquad(
                ConfigureLendingSquadRequest(
                    identifier = created.lendingId,
                    actorPeerId = "peer-member",
                    multisigAddress = "Squad111111111111111111111111111111111111"
                )
            )
            throw AssertionError("configureSquad should fail for non-owners")
        } catch (expected: IllegalStateException) {
            assertEquals("owner_only_squad_configuration", expected.message)
        }
    }

    @Test
    fun createSquad_createsAndPersistsSharedCustodyForOwner() = kotlinx.coroutines.runBlocking {
        val created = service.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#truecustody",
                displayName = "#truecustody",
                creatorPeerId = "peer-owner",
                creatorWalletAddress = "Wallet111111111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                minimumVoteCount = 2,
                stakeTokenMint = "USDC",
                stakeTokenSymbol = "USDC"
            )
        )

        val updated = service.createSquad(
            CreateLendingSquadRequest(
                identifier = created.lendingId,
                actorPeerId = "peer-owner",
                memberWalletAddresses = listOf(
                    "Wallet111111111111111111111111111111111",
                    "Wallet222222222222222222222222222222222",
                    "Wallet333333333333333333333333333333333"
                )
            )
        )

        val escrow = database.lendingDao().getEscrowAccount(created.lendingId)

        assertEquals(fakeSquadsService.createdMultisigAddress, updated.escrowMultisigAddress)
        assertNotNull(escrow)
        assertEquals(fakeSquadsService.createdMultisigAddress, escrow!!.multisigAddress)
        assertEquals(fakeSquadsService.createdVaultAddress, escrow.vaultAddress)
        assertEquals(3, fakeSquadsService.lastCreatedMemberWallets.size)
    }

    @Test
    fun createSquad_requiresChannelOwner() = kotlinx.coroutines.runBlocking {
        val created = service.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#ownerguard",
                displayName = "#ownerguard",
                creatorPeerId = "peer-owner",
                creatorWalletAddress = "Wallet111111111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                minimumVoteCount = 2,
                stakeTokenMint = "USDC",
                stakeTokenSymbol = "USDC"
            )
        )

        try {
            service.createSquad(
                CreateLendingSquadRequest(
                    identifier = created.lendingId,
                    actorPeerId = "peer-member",
                    memberWalletAddresses = listOf(
                        "Wallet111111111111111111111111111111111",
                        "Wallet222222222222222222222222222222222",
                        "Wallet333333333333333333333333333333333"
                    )
                )
            )
            throw AssertionError("createSquad should fail for non-owners")
        } catch (expected: IllegalStateException) {
            assertEquals("owner_only_squad_configuration", expected.message)
        }
    }

    private class FakeSquadsService : SquadsService {
        val createdMultisigAddress = "Squad111111111111111111111111111111111111"
        val createdVaultAddress = "Vault111111111111111111111111111111111111"
        var lastCreatedMemberWallets: List<String> = emptyList()

        override fun config(): SquadsConfig = SquadsConfig()

        override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> =
            Result.failure(IllegalStateException("squad_not_configured"))

        override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> =
            Result.success(
                SquadsMultisigState(
                    multisigAddress = multisigAddress,
                    threshold = REQUIRED_LOAN_APPROVAL_COUNT,
                    transactionIndex = 0L,
                    staleTransactionIndex = 0L,
                    memberCount = TARGET_LOAN_APPROVAL_MEMBER_COUNT
                )
            )

        override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> =
            Result.success(
                SquadsProgramConfigState(
                    treasuryAddress = "Treasury1111111111111111111111111111111111",
                    multisigCreationFeeLamports = 0L
                )
            )

        override suspend fun createLendingMultisig(
            memberWallets: List<String>,
            threshold: Int
        ): Result<SquadsCreatedMultisig> {
            lastCreatedMemberWallets = memberWallets
            return Result.success(
                SquadsCreatedMultisig(
                    multisigAddress = createdMultisigAddress,
                    vaultAddress = createdVaultAddress,
                    txSignature = "tx-sig",
                    threshold = threshold,
                    memberCount = memberWallets.size,
                    cluster = DEFAULT_SQUADS_CLUSTER
                )
            )
        }

        override suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> =
            Result.failure(IllegalStateException("squad_not_configured"))

        override suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> =
            Result.failure(IllegalStateException("squad_not_configured"))

        override suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> =
            Result.failure(IllegalStateException("squad_not_configured"))

        override suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?> =
            Result.success(null)
    }
}
