package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.BluetoothMeshService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandProcessorRoleCommandTest {
    private lateinit var state: ChatState
    private lateinit var dataManager: DataManager
    private lateinit var messageManager: MessageManager
    private lateinit var channelManager: ChannelManager
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var meshService: BluetoothMeshService

    private val myPeerID = "peer-me"
    private val channelKey = "mesh:#ops"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        state = ChatState()
        dataManager = DataManager(context)
        messageManager = MessageManager(state)
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
                    override fun getMyPeerID(): String = myPeerID
                }
            )
        )

        meshService = BluetoothMeshService(context)

        channelManager.joinChannel("#ops", null, myPeerID, null)
        channelManager.assignChannelCreator(channelKey, myPeerID)
        channelManager.switchToChannel(channelKey)
    }

    @Test
    fun processCommand_memberAdminRejectsChannelArgument() {
        val currentVersion = channelManager.getChannelRoleVersion(channelKey)
        val result = commandProcessor.processCommand(
            command = "/channel member admin #other @alice",
            meshService = meshService,
            myPeerID = myPeerID,
            onSendMessage = { _, _, _ -> },
            viewModel = null
        )

        assertNull(result)
        assertEquals(currentVersion, channelManager.getChannelRoleVersion(channelKey))
    }

    @Test
    fun processCommand_memberMemberRejectsChannelArgument() {
        val currentVersion = channelManager.getChannelRoleVersion(channelKey)
        val result = commandProcessor.processCommand(
            command = "/channel member member #other @alice",
            meshService = meshService,
            myPeerID = myPeerID,
            onSendMessage = { _, _, _ -> },
            viewModel = null
        )

        assertNull(result)
        assertEquals(currentVersion, channelManager.getChannelRoleVersion(channelKey))
    }

    @Test
    fun processCommand_memberAdminWithoutNicknameReturnsHint() {
        val result = commandProcessor.processCommand(
            command = "/channel member admin",
            meshService = meshService,
            myPeerID = myPeerID,
            onSendMessage = { _, _, _ -> },
            viewModel = null
        )

        assertNotNull(result)
        assertEquals("/channel member admin @", result?.prefillText)
    }
}
