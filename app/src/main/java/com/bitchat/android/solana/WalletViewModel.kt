package com.bitchat.android.solana

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Solana wallet screen.
 * Manages wallet creation, balance refresh, and UI state.
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletService: SolanaWalletService,
    private val rpcService: SolanaRpcService,
    private val paymentManager: SolanaPaymentManager
) : ViewModel() {

    companion object {
        private const val TAG = "WalletViewModel"
    }

    // Wallet state
    private val _walletState = MutableStateFlow<WalletUiState>(WalletUiState.Loading)
    val walletState: StateFlow<WalletUiState> = _walletState.asStateFlow()

    // Mnemonic display (only shown once after creation)
    private val _mnemonicPhrase = MutableStateFlow<String?>(null)
    val mnemonicPhrase: StateFlow<String?> = _mnemonicPhrase.asStateFlow()

    // Balance refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Error messages
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Restore input
    private val _showRestoreDialog = MutableStateFlow(false)
    val showRestoreDialog: StateFlow<Boolean> = _showRestoreDialog.asStateFlow()

    // Send dialog state
    private val _showSendDialog = MutableStateFlow(false)
    val showSendDialog: StateFlow<Boolean> = _showSendDialog.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _sendSuccess = MutableStateFlow<String?>(null)
    val sendSuccess: StateFlow<String?> = _sendSuccess.asStateFlow()

    // Transaction history
    val recentTransactions: StateFlow<List<QueuedTransactionEntity>> =
        paymentManager.observeRecentTransactions()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _showTransactionHistory = MutableStateFlow(false)
    val showTransactionHistory: StateFlow<Boolean> = _showTransactionHistory.asStateFlow()

    // Cached SOL/USD price
    private var cachedSolPrice: Double? = null

    init {
        loadWalletState()
        fetchSolPrice()
    }

    private fun loadWalletState() {
        viewModelScope.launch {
            if (walletService.hasWallet()) {
                // Observe active wallet from Room
                walletService.observeActiveWallet().collect { wallet ->
                    if (wallet != null) {
                        val solAmount = wallet.lastBalanceLamports.toDouble() / 1_000_000_000.0
                        val usdString = cachedSolPrice?.let { price ->
                            val usd = solAmount * price
                            "$${"%,.0f".format(usd)}"
                        }
                        _walletState.value = WalletUiState.Ready(
                            address = wallet.publicKey,
                            shortAddress = walletService.getShortAddress() ?: "",
                            balanceLamports = wallet.lastBalanceLamports,
                            balanceSol = walletService.lamportsToSol(wallet.lastBalanceLamports),
                            balanceUsd = usdString,
                            lastUpdated = wallet.lastBalanceUpdatedAt,
                            label = wallet.label
                        )
                    } else {
                        _walletState.value = WalletUiState.NoWallet
                    }
                }
            } else {
                _walletState.value = WalletUiState.NoWallet
            }
        }
    }

    fun createWallet() {
        viewModelScope.launch {
            _walletState.value = WalletUiState.Loading
            val result = walletService.createWallet()
            result.onSuccess { mnemonic ->
                _mnemonicPhrase.value = mnemonic
                Log.d(TAG, "Wallet created successfully")
                // loadWalletState will be triggered by Room Flow observation
                loadWalletState()
            }.onFailure { error ->
                _errorMessage.value = "Failed to create wallet: ${error.message}"
                _walletState.value = WalletUiState.NoWallet
                Log.e(TAG, "Wallet creation failed", error)
            }
        }
    }

    fun restoreWallet(mnemonicPhrase: String) {
        viewModelScope.launch {
            _walletState.value = WalletUiState.Loading
            _showRestoreDialog.value = false
            val result = walletService.restoreWallet(mnemonicPhrase.trim())
            result.onSuccess {
                _mnemonicPhrase.value = null
                Log.d(TAG, "Wallet restored successfully")
                loadWalletState()
            }.onFailure { error ->
                _errorMessage.value = "Invalid recovery phrase: ${error.message}"
                _walletState.value = WalletUiState.NoWallet
                Log.e(TAG, "Wallet restore failed", error)
            }
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            _isRefreshing.value = true
            // Fetch price and balance in parallel
            val priceJob = launch {
                rpcService.getSolPrice().onSuccess { price ->
                    cachedSolPrice = price
                }
            }
            val result = walletService.refreshBalance()
            result.onFailure { error ->
                val message = if (isNetworkError(error)) {
                    "Offline — balance will update when internet is available"
                } else {
                    "Balance refresh failed: ${error.message}"
                }
                _errorMessage.value = message
                Log.e(TAG, "Balance refresh failed", error)
            }
            priceJob.join()
            _isRefreshing.value = false
        }
    }

    private fun isNetworkError(error: Throwable): Boolean {
        return error is java.net.UnknownHostException ||
                error is java.net.ConnectException ||
                error is java.net.SocketTimeoutException ||
                error.message?.contains("Unable to resolve host") == true ||
                error.message?.contains("No address associated with hostname") == true
    }

    private fun fetchSolPrice() {
        viewModelScope.launch {
            rpcService.getSolPrice().onSuccess { price ->
                cachedSolPrice = price
                // Update current state if ready
                val current = _walletState.value
                if (current is WalletUiState.Ready) {
                    val solAmount = current.balanceLamports.toDouble() / 1_000_000_000.0
                    val usd = solAmount * price
                    _walletState.value = current.copy(balanceUsd = "$${"%,.0f".format(usd)}")
                }
            }
        }
    }

    fun showMnemonicBackup() {
        val mnemonic = walletService.getMnemonic()
        if (mnemonic != null) {
            _mnemonicPhrase.value = mnemonic
        } else {
            _errorMessage.value = "Recovery phrase not available"
        }
    }

    fun deleteWallet() {
        viewModelScope.launch {
            walletService.deleteWallet()
            _mnemonicPhrase.value = null
            _walletState.value = WalletUiState.NoWallet
            Log.d(TAG, "Wallet deleted")
        }
    }

    fun dismissMnemonic() {
        _mnemonicPhrase.value = null
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun showRestoreDialog() {
        _showRestoreDialog.value = true
    }

    fun dismissRestoreDialog() {
        _showRestoreDialog.value = false
    }

    fun showTransactionHistory() {
        _showTransactionHistory.value = true
    }

    fun dismissTransactionHistory() {
        _showTransactionHistory.value = false
    }

    fun showSendDialog() {
        _showSendDialog.value = true
    }

    fun dismissSendDialog() {
        _showSendDialog.value = false
    }

    fun dismissSendSuccess() {
        _sendSuccess.value = null
    }

    fun sendSol(recipientAddress: String, amountSol: Double) {
        viewModelScope.launch {
            _isSending.value = true
            val result = paymentManager.queuePayment(recipientAddress, amountSol)
            result.onSuccess {
                _showSendDialog.value = false
                _sendSuccess.value = "Payment queued — ${"%.4f".format(amountSol)} SOL to ${recipientAddress.take(8)}..."
                // Try to refresh balance after broadcast, but silently skip if offline
                kotlinx.coroutines.delay(3000)
                silentRefreshBalance()
                kotlinx.coroutines.delay(5000)
                silentRefreshBalance()
            }.onFailure { error ->
                _errorMessage.value = "Send failed: ${error.message}"
            }
            _isSending.value = false
        }
    }

    /**
     * Refresh balance without showing error dialogs on network failure.
     * Used after sending — the payment is already queued, so network errors
     * for balance refresh should not alarm the user.
     */
    private suspend fun silentRefreshBalance() {
        try {
            rpcService.getSolPrice().onSuccess { price ->
                cachedSolPrice = price
            }
            walletService.refreshBalance()
            // Balance update will flow through Room observation automatically
        } catch (e: Exception) {
            Log.d(TAG, "Silent balance refresh skipped (offline): ${e.message}")
        }
    }
}

/**
 * UI state for the wallet screen.
 */
sealed class WalletUiState {
    data object Loading : WalletUiState()
    data object NoWallet : WalletUiState()
    data class Ready(
        val address: String,
        val shortAddress: String,
        val balanceLamports: Long,
        val balanceSol: String,
        val balanceUsd: String? = null,
        val lastUpdated: Long,
        val label: String
    ) : WalletUiState()
}
