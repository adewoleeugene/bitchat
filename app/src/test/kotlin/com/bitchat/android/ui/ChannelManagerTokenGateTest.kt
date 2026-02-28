package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.solana.GateDecision
import com.bitchat.android.solana.TokenGateService
import com.bitchat.android.solana.TokenGateValidationResult
import com.bitchat.android.solana.ValidationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChannelManagerTokenGateTest {
    private lateinit var context: Context
    private lateinit var state: ChatState
    private lateinit var messageManager: MessageManager
    private lateinit var dataManager: DataManager
    private lateinit var channelManager: ChannelManager
    private val tokenGateService: TokenGateService = mock()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        state = ChatState()
        messageManager = MessageManager(state)
        dataManager = DataManager(context)
        channelManager = ChannelManager(
            state = state,
            messageManager = messageManager,
            dataManager = dataManager,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined)
        )
        channelManager.tokenGateService = tokenGateService
    }

    @Test
    fun joinChannel_tokenGateDenied_doesNotJoin() = runBlocking {
        val channel = "#vip"
        val key = ChannelKeys.create(ChannelID.Mesh, channel)

        whenever(tokenGateService.isTokenGated(key)).thenReturn(true)
        whenever(tokenGateService.validateEligibility(eq(key), eq(ValidationMode.PREFER_CACHE_THEN_ONLINE)))
            .thenReturn(
                Result.success(
                    TokenGateValidationResult(
                        decision = GateDecision.DENY,
                        reasonCode = "RPC_DENY",
                        userBalance = 0L,
                        requiredBalance = 1L,
                        tokenSymbol = "NFT",
                        tokenDecimals = 0,
                        fromCache = false,
                        validUntil = System.currentTimeMillis() + 60_000L
                    )
                )
            )
        whenever(tokenGateService.formatRequirementText(any())).thenReturn("need at least 1 NFT")

        val immediate = channelManager.joinChannel(channel, null, "peer-me", ChannelID.Mesh)

        assertFalse(immediate)
        assertFalse(state.getJoinedChannelsValue().contains(key))
    }

    @Test
    fun joinChannel_tokenGateAllow_joinsAfterAsyncValidation() = runBlocking {
        val channel = "#vip"
        val key = ChannelKeys.create(ChannelID.Mesh, channel)

        whenever(tokenGateService.isTokenGated(key)).thenReturn(true)
        whenever(tokenGateService.validateEligibility(eq(key), eq(ValidationMode.PREFER_CACHE_THEN_ONLINE)))
            .thenReturn(
                Result.success(
                    TokenGateValidationResult(
                        decision = GateDecision.ALLOW,
                        reasonCode = "RPC_ALLOW",
                        userBalance = 2L,
                        requiredBalance = 1L,
                        tokenSymbol = "NFT",
                        tokenDecimals = 0,
                        fromCache = false,
                        validUntil = System.currentTimeMillis() + 60_000L
                    )
                )
            )

        val immediate = channelManager.joinChannel(channel, null, "peer-me", ChannelID.Mesh)

        assertFalse(immediate)
        assertTrue(state.getJoinedChannelsValue().contains(key))
    }
}
