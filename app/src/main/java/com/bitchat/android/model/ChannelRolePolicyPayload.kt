package com.bitchat.android.model

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * Mesh-sync payload for channel role state.
 *
 * A payload is a full snapshot for one channel at a specific version.
 * Admins absent from [adminPeerIds] are treated as members.
 */
data class ChannelRolePolicyPayload(
    val channelKey: String,
    val ownerPeerId: String,
    val adminPeerIds: List<String>,
    val roleVersion: Long,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun encode(): ByteArray {
        return gson.toJson(this).toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val gson = Gson()

        fun decode(data: ByteArray): ChannelRolePolicyPayload? {
            return try {
                val json = JsonParser.parseString(String(data, Charsets.UTF_8)).asJsonObject
                val channelKey = json.get("channelKey")?.asString ?: return null
                val ownerPeerId = json.get("ownerPeerId")?.asString ?: return null
                val roleVersion = json.get("roleVersion")?.asLong ?: return null
                val updatedAt = json.get("updatedAt")?.asLong ?: System.currentTimeMillis()

                val admins = json.getAsJsonArray("adminPeerIds")
                    ?.mapNotNull { element ->
                        element?.asString?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    ?: emptyList()

                ChannelRolePolicyPayload(
                    channelKey = channelKey,
                    ownerPeerId = ownerPeerId,
                    adminPeerIds = admins.distinct(),
                    roleVersion = roleVersion,
                    updatedAt = updatedAt
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
