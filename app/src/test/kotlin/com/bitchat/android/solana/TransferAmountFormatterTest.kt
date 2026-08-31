package com.bitchat.android.solana

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferAmountFormatterTest {

    @Test
    fun formatForDisplay_formatsNativeSol() {
        val label = TransferAmountFormatter.formatForDisplay(
            amountAtomic = 1_250_000_000L,
            asset = TransferAsset(
                kind = TransferAssetKind.NATIVE_SOL,
                symbol = "SOL",
                decimals = 9
            )
        )

        assertEquals("1.25 SOL", label)
    }

    @Test
    fun formatForDisplay_formatsSplTokensUsingProvidedDecimals() {
        val label = TransferAmountFormatter.formatForDisplay(
            amountAtomic = 50_125_000L,
            asset = TransferAsset(
                kind = TransferAssetKind.SPL_TOKEN,
                mintAddress = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                symbol = "USDC",
                decimals = 6
            )
        )

        assertEquals("50.125 USDC", label)
    }
}
