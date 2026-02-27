package com.bitchat.android.solana

import android.app.Application
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NftAvatarServiceTest {

    private lateinit var service: NftAvatarService

    @Before
    fun setUp() {
        val context = Application()
        val rpcService = mock<SolanaRpcService>()
        val httpClient = OkHttpClient()
        service = NftAvatarService(context, rpcService, httpClient)
    }

    @Test
    fun resolveUri_ipfsScheme_usesCloudflareGateway() {
        val uri = "ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"
        val resolved = service.resolveUri(uri)
        assertEquals(
            "https://cloudflare-ipfs.com/ipfs/QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG",
            resolved
        )
    }

    @Test
    fun resolveUri_arweaveScheme_usesArweaveNet() {
        val uri = "ar://abc123def456"
        val resolved = service.resolveUri(uri)
        assertEquals("https://arweave.net/abc123def456", resolved)
    }

    @Test
    fun resolveUri_httpsScheme_passthrough() {
        val uri = "https://nftstorage.link/ipfs/QmABC123"
        val resolved = service.resolveUri(uri)
        assertEquals(uri, resolved)
    }

    @Test
    fun resolveUri_httpScheme_passthrough() {
        val uri = "http://example.com/image.png"
        val resolved = service.resolveUri(uri)
        assertEquals(uri, resolved)
    }

    @Test
    fun resolveUri_unknownScheme_returnsNull() {
        assertNull(service.resolveUri("ftp://example.com/image.png"))
        assertNull(service.resolveUri("data:image/png;base64,abc"))
        assertNull(service.resolveUri(""))
        assertNull(service.resolveUri("just-a-string"))
    }

    @Test
    fun resolveUri_ipfsWithSubpath_preserved() {
        val uri = "ipfs://QmABC123/0.png"
        val resolved = service.resolveUri(uri)
        assertEquals("https://cloudflare-ipfs.com/ipfs/QmABC123/0.png", resolved)
    }
}
