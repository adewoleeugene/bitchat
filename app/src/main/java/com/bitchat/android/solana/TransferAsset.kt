package com.bitchat.android.solana

import java.math.BigDecimal
import java.math.RoundingMode

enum class TransferAssetKind {
    NATIVE_SOL,
    SPL_TOKEN
}

data class TransferAsset(
    val kind: TransferAssetKind,
    val mintAddress: String? = null,
    val symbol: String,
    val decimals: Int
)

object TransferAmountFormatter {
    fun formatAtomicAmount(amountAtomic: Long, decimals: Int): String {
        if (decimals <= 0) return amountAtomic.toString()
        val scaled = BigDecimal.valueOf(amountAtomic).movePointLeft(decimals)
        return scaled.stripTrailingZeros().toPlainString().ifBlank { "0" }
    }

    fun formatForDisplay(amountAtomic: Long, asset: TransferAsset, maxScale: Int = 4): String {
        val scaled = BigDecimal.valueOf(amountAtomic).movePointLeft(asset.decimals)
        val normalized = scaled.setScale(maxScale.coerceAtLeast(0), RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
        return "$normalized ${asset.symbol}"
    }
}
