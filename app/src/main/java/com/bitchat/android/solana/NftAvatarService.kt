package com.bitchat.android.solana

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Service for fetching, caching, and serving NFT avatar images.
 * Resolves NFT mint addresses to images via DAS getAsset, then downloads
 * and caches the images locally (memory LRU + disk with 24h TTL).
 */
@Singleton
class NftAvatarService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rpcService: SolanaRpcService,
    @Named("solanaOkHttp") private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "NftAvatarService"
        private const val AVATAR_CACHE_DIR = "nft_avatars"
        private const val MAX_IMAGE_SIZE_BYTES = 2 * 1024 * 1024 // 2MB download limit
        private const val AVATAR_MAX_DIM = 256 // Downscale to 256px max
        private const val MEMORY_CACHE_SIZE = 30
        private const val DISK_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        private val IPFS_GATEWAYS = listOf(
            "https://cloudflare-ipfs.com/ipfs/",
            "https://ipfs.io/ipfs/",
            "https://gateway.pinata.cloud/ipfs/"
        )
    }

    // In-memory LRU cache: mintAddress -> Bitmap
    private val memoryCache = object : LinkedHashMap<String, Bitmap>(
        MEMORY_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            return size > MEMORY_CACHE_SIZE
        }
    }

    // Track in-flight requests to avoid duplicate fetches
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Get avatar bitmap for an NFT mint, checking memory -> disk -> network.
     * Returns null if unavailable.
     */
    suspend fun getAvatar(mintAddress: String, ownerAddress: String? = null): Bitmap? {
        // Require current ownership before serving cached/network avatar when owner is provided.
        if (ownerAddress != null) {
            val owns = verifyOwnership(mintAddress, ownerAddress)
            if (!owns) {
                invalidateCache(mintAddress)
                Log.w(TAG, "Owner $ownerAddress does not hold NFT $mintAddress")
                return null
            }
        }

        // 1. Memory cache
        synchronized(memoryCache) {
            memoryCache[mintAddress]?.let { return it }
        }

        // 2. Disk cache (if not expired)
        loadFromDisk(mintAddress)?.let { bmp ->
            synchronized(memoryCache) { memoryCache[mintAddress] = bmp }
            return bmp
        }

        // 3. Deduplicated network fetch
        val deferred = inFlightRequests.getOrPut(mintAddress) {
            serviceScope.async {
                try {
                    fetchAndCache(mintAddress)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch avatar for $mintAddress: ${e.message}")
                    null
                } finally {
                    inFlightRequests.remove(mintAddress)
                }
            }
        }
        return deferred.await()
    }

    /**
     * Invalidate cached avatar for a specific mint (e.g., when peer changes their NFT).
     */
    fun invalidateCache(mintAddress: String) {
        synchronized(memoryCache) { memoryCache.remove(mintAddress) }
        getCacheFile(mintAddress).delete()
    }

    private suspend fun fetchAndCache(mintAddress: String): Bitmap? {
        // Step 1: Get image URI from DAS getAsset
        val imageUri = getImageUri(mintAddress) ?: return null

        // Step 2: Resolve and download image
        val resolvedUrl = resolveUri(imageUri) ?: return null
        val imageBytes = downloadImage(resolvedUrl) ?: return null

        // Step 3: Decode and downscale
        val bitmap = decodeBitmap(imageBytes) ?: return null

        // Step 4: Save to disk cache
        saveToDisk(mintAddress, bitmap)

        // Step 5: Add to memory cache
        synchronized(memoryCache) { memoryCache[mintAddress] = bitmap }

        return bitmap
    }

    private suspend fun getImageUri(mintAddress: String): String? {
        val result = rpcService.getNftImageUri(mintAddress).getOrNull()
        if (result.isNullOrBlank()) return null

        // If it's a direct image URL, return it
        if (result.startsWith("https://") || result.startsWith("http://") ||
            result.startsWith("ipfs://") || result.startsWith("ar://")
        ) {
            return result
        }

        // It might be a json_uri — fetch the JSON and extract "image" field
        return fetchImageFromJsonUri(result)
    }

    private fun fetchImageFromJsonUri(jsonUri: String): String? {
        return try {
            val url = resolveUri(jsonUri) ?: return null
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JsonParser.parseString(body).asJsonObject
                json.get("image")?.asString
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch JSON metadata: ${e.message}")
            null
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resolveUri(uri: String): String? {
        return when {
            uri.startsWith("ipfs://") -> {
                val cid = uri.removePrefix("ipfs://")
                IPFS_GATEWAYS[0] + cid
            }
            uri.startsWith("ar://") -> {
                "https://arweave.net/" + uri.removePrefix("ar://")
            }
            uri.startsWith("https://") || uri.startsWith("http://") -> uri
            else -> null
        }
    }

    private fun downloadImage(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return null
                val contentLength = resp.body?.contentLength() ?: -1
                if (contentLength > MAX_IMAGE_SIZE_BYTES) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.size > MAX_IMAGE_SIZE_BYTES) return null
                bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image download failed for $url: ${e.message}")
            null
        }
    }

    private fun decodeBitmap(data: ByteArray): Bitmap? {
        return try {
            // First pass: get dimensions only
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)

            // Calculate sample size for target max dimension
            val maxSrc = maxOf(opts.outWidth, opts.outHeight)
            var sampleSize = 1
            while (maxSrc / sampleSize > AVATAR_MAX_DIM * 2) {
                sampleSize *= 2
            }

            // Second pass: decode
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val raw = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts) ?: return null

            // Final scale to exact target dimension
            val scale = (maxOf(raw.width, raw.height).toFloat() / AVATAR_MAX_DIM).coerceAtLeast(1f)
            if (scale > 1f) {
                val newW = (raw.width / scale).toInt().coerceAtLeast(1)
                val newH = (raw.height / scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(raw, newW, newH, true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else {
                raw
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap decode failed: ${e.message}")
            null
        }
    }

    private suspend fun verifyOwnership(mintAddress: String, ownerAddress: String): Boolean {
        return try {
            val balance = rpcService.getTokenBalance(ownerAddress, mintAddress).getOrElse { 0L }
            balance > 0
        } catch (_: Exception) {
            false
        }
    }

    // Disk cache helpers

    private fun getCacheDir(): File {
        return File(context.filesDir, AVATAR_CACHE_DIR).apply { mkdirs() }
    }

    private fun getCacheFile(mintAddress: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(mintAddress.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return File(getCacheDir(), "$hash.jpg")
    }

    private fun loadFromDisk(mintAddress: String): Bitmap? {
        val file = getCacheFile(mintAddress)
        if (!file.exists()) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > DISK_CACHE_TTL_MS) {
            file.delete()
            return null
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToDisk(mintAddress: String, bitmap: Bitmap) {
        try {
            val file = getCacheFile(mintAddress)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
        } catch (_: Exception) {}
    }
}
