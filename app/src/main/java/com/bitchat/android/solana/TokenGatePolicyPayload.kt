package com.bitchat.android.solana

import com.bitchat.android.data.local.entities.TokenGateConfigEntity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object TokenGatePolicyAction {
    const val UPSERT = "UPSERT"
    const val REMOVE = "REMOVE"
}

/**
 * Mesh-sync payload for token-gate policies.
 */
data class TokenGatePolicyPayload(
    val action: String,
    val channelKey: String,
    val gateType: String? = null,
    val tokenMintAddress: String? = null,
    val minBalance: Long? = null,
    val tokenSymbol: String? = null,
    val tokenDecimals: Int? = null,
    val creatorPublicKey: String? = null,
    val policyVersion: Int,
    val gateHash: String,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun encode(): ByteArray {
        return gson.toJson(this).toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val gson = Gson()

        fun decode(data: ByteArray): TokenGatePolicyPayload? {
            return try {
                val json = JsonParser.parseString(String(data, Charsets.UTF_8)).asJsonObject
                fromJson(json)
            } catch (_: Exception) {
                null
            }
        }

        private fun fromJson(json: JsonObject): TokenGatePolicyPayload? {
            return try {
                TokenGatePolicyPayload(
                    action = json.get("action")?.asString ?: return null,
                    channelKey = json.get("channelKey")?.asString ?: return null,
                    gateType = json.get("gateType")?.asString,
                    tokenMintAddress = json.get("tokenMintAddress")?.asString,
                    minBalance = json.get("minBalance")?.asLong,
                    tokenSymbol = json.get("tokenSymbol")?.asString,
                    tokenDecimals = json.get("tokenDecimals")?.asInt,
                    creatorPublicKey = json.get("creatorPublicKey")?.asString,
                    policyVersion = json.get("policyVersion")?.asInt ?: return null,
                    gateHash = json.get("gateHash")?.asString ?: return null,
                    updatedAt = json.get("updatedAt")?.asLong ?: System.currentTimeMillis()
                )
            } catch (_: Exception) {
                null
            }
        }

        fun fromConfig(config: TokenGateConfigEntity): TokenGatePolicyPayload {
            return TokenGatePolicyPayload(
                action = TokenGatePolicyAction.UPSERT,
                channelKey = config.channelKey,
                gateType = config.gateType,
                tokenMintAddress = config.tokenMintAddress,
                minBalance = config.minBalance,
                tokenSymbol = config.tokenSymbol,
                tokenDecimals = config.tokenDecimals,
                creatorPublicKey = config.creatorPublicKey,
                policyVersion = config.policyVersion,
                gateHash = config.gateHash,
                updatedAt = System.currentTimeMillis()
            )
        }

        fun removeFromConfig(config: TokenGateConfigEntity): TokenGatePolicyPayload {
            return TokenGatePolicyPayload(
                action = TokenGatePolicyAction.REMOVE,
                channelKey = config.channelKey,
                policyVersion = config.policyVersion + 1,
                gateHash = config.gateHash,
                creatorPublicKey = config.creatorPublicKey,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}
