package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandProcessorLendingCommandTest {
    private lateinit var state: ChatState
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var channelManager: ChannelManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        state = ChatState()
        val dataManager = DataManager(context)
        val messageManager = MessageManager(state)
        channelManager = ChannelManager(
            state = state,
            messageManager = messageManager,
            dataManager = dataManager,
            coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        commandProcessor = CommandProcessor(
            state = state,
            messageManager = messageManager,
            channelManager = channelManager,
            privateChatManager = PrivateChatManager(
                state = state,
                messageManager = messageManager,
                dataManager = dataManager,
                noiseSessionDelegate = object : NoiseSessionDelegate {
                    override fun hasEstablishedSession(peerID: String): Boolean = true
                    override fun initiateHandshake(peerID: String) = Unit
                    override fun getMyPeerID(): String = "peer-me"
                }
            )
        )
    }

    @Test
    fun getAllSlashCommands_includesLendingCommandInGlobalContext() {
        val commands = commandProcessor.getAllSlashCommands("peer-me").map { it.command }

        assertTrue("/lending" in commands)
    }

    @Test
    fun selectCommandSuggestion_lendingCreateReturnsExpectedHint() {
        val result = commandProcessor.selectCommandSuggestion(
            CommandSuggestion("/lending create", emptyList(), "#channel <stake_amount> <mint>", "create lending channel")
        )

        assertEquals("/lending create #", result.prefillText)
    }

    @Test
    fun selectCommandSuggestion_lendingRequestReturnsExpectedHint() {
        val result = commandProcessor.selectCommandSuggestion(
            CommandSuggestion(
                "/lending request",
                emptyList(),
                "[group] [#channel|lendingId] <amount> <days> <purpose>",
                "request a loan"
            )
        )

        assertEquals("/lending request ", result.prefillText)
        assertTrue(result.hintText.orEmpty().contains("group"))
    }
}
