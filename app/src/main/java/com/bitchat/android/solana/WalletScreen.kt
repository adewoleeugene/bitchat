package com.bitchat.android.solana

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.models.TransactionStatus
import com.bitchat.android.ui.theme.AppIcons
import com.bitchat.android.ui.theme.rememberAppIconPainter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitchat.android.data.local.entities.WalletEntity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.BitchatShapes
import com.bitchat.android.ui.theme.SatoshiFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onBack: () -> Unit,
    getPeersWithSolana: () -> List<Pair<String, String>> = { emptyList() },
    viewModel: WalletViewModel = hiltViewModel()
) {
    val walletState by viewModel.walletState.collectAsState()
    val mnemonicPhrase by viewModel.mnemonicPhrase.collectAsState()
    val privateKeyBase58 by viewModel.privateKeyBase58.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showRestoreDialog by viewModel.showRestoreDialog.collectAsState()
    val showImportPrivateKeyDialog by viewModel.showImportPrivateKeyDialog.collectAsState()
    val showSendScreen by viewModel.showSendDialog.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val sendSuccess by viewModel.sendSuccess.collectAsState()
    val showTxHistory by viewModel.showTransactionHistory.collectAsState()
    val transactions by viewModel.recentTransactions.collectAsState()
    val allWallets by viewModel.allWallets.collectAsState()
    val initializationIssue by viewModel.initializationIssue.collectAsState()
    val context = LocalContext.current
    var showExportAuthMethodDialog by remember { mutableStateOf(false) }
    var showSetExportPasscodeDialog by remember { mutableStateOf(false) }
    var showVerifyExportPasscodeDialog by remember { mutableStateOf(false) }

    // Transaction history screen (full screen overlay)
    if (showTxHistory) {
        val activeAddress = (walletState as? WalletUiState.Ready)?.address
        TransactionHistoryScreen(
            transactions = transactions,
            activeWalletAddress = activeAddress,
            onBack = { viewModel.dismissTransactionHistory() }
        )
        return
    }

    // Send screen (full screen overlay)
    if (showSendScreen) {
        SendScreen(
            onBack = { viewModel.dismissSendDialog() },
            getPeersWithSolana = getPeersWithSolana,
            isSending = isSending,
            onSend = { address, amount -> viewModel.sendSol(address, amount) }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Solana Wallet",
                        fontFamily = SatoshiFamily,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = rememberAppIconPainter(AppIcons.ArrowBack), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BitchatColors.Background,
                    titleContentColor = BitchatColors.TextPrimary,
                    navigationIconContentColor = BitchatColors.TextPrimary
                )
            )
        },
        containerColor = BitchatColors.Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (walletState) {
                is WalletUiState.Loading -> {
                    Spacer(modifier = Modifier.height(64.dp))
                    CircularProgressIndicator(color = BitchatColors.ButtonPrimaryBg)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Loading wallet...",
                        fontFamily = SatoshiFamily,
                        color = BitchatColors.TextSecondary
                    )
                }

                is WalletUiState.NoWallet -> {
                    NoWalletContent(
                        onCreateWallet = { viewModel.createWallet() },
                        onRestoreWallet = { viewModel.showRestoreDialog() },
                        onImportPrivateKey = { viewModel.showImportPrivateKeyDialog() },
                        initializationIssue = initializationIssue
                    )
                }

                is WalletUiState.Ready -> {
                    val state = walletState as WalletUiState.Ready
                    WalletReadyContent(
                        state = state,
                        wallets = allWallets,
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refreshBalance() },
                        onDelete = { viewModel.deleteWallet() },
                        onCreateMnemonicWallet = { viewModel.createWallet() },
                        onSwitchWallet = { publicKey -> viewModel.switchActiveWallet(publicKey) },
                        onImportPrivateKey = { viewModel.showImportPrivateKeyDialog() },
                        onExportPrivateKey = { showExportAuthMethodDialog = true },
                        onExportRecoveryPhrase = { viewModel.showMnemonicBackup() },
                        onSend = { viewModel.showSendDialog() },
                        onTransactionHistory = { viewModel.showTransactionHistory() }
                    )
                }
            }
        }
    }

    // Mnemonic backup dialog
    if (mnemonicPhrase != null) {
        MnemonicBackupDialog(
            mnemonic = mnemonicPhrase!!,
            onDismiss = { viewModel.dismissMnemonic() }
        )
    }

    // Private key export dialog
    if (privateKeyBase58 != null) {
        PrivateKeyExportDialog(
            privateKeyBase58 = privateKeyBase58!!,
            onDismiss = { viewModel.dismissPrivateKeyExport() }
        )
    }

    // Error dialog
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = {
                Text(
                    "Error",
                    fontFamily = SatoshiFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    errorMessage!!,
                    fontFamily = SatoshiFamily,
                    color = BitchatColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text("OK", fontFamily = SatoshiFamily)
                }
            }
        )
    }

    // Send success toast
    if (sendSuccess != null) {
        LaunchedEffect(sendSuccess) {
            Toast.makeText(context, sendSuccess, Toast.LENGTH_SHORT).show()
            viewModel.dismissSendSuccess()
        }
    }

    // Restore dialog
    if (showRestoreDialog) {
        RestoreWalletDialog(
            onRestore = { phrase -> viewModel.restoreWallet(phrase) },
            onDismiss = { viewModel.dismissRestoreDialog() }
        )
    }

    if (showImportPrivateKeyDialog) {
        ImportPrivateKeyDialog(
            onImport = { key -> viewModel.importPrivateKey(key) },
            onDismiss = { viewModel.dismissImportPrivateKeyDialog() }
        )
    }

    if (showExportAuthMethodDialog) {
        ExportAuthMethodDialog(
            onDismiss = { showExportAuthMethodDialog = false },
            onUseDeviceAuth = {
                showExportAuthMethodDialog = false
                authenticateThenExportPrivateKey(
                    context = context,
                    viewModel = viewModel,
                    onFallbackToPasscode = {
                        if (viewModel.hasExportPasscodeConfigured()) {
                            showVerifyExportPasscodeDialog = true
                        } else {
                            showSetExportPasscodeDialog = true
                        }
                    }
                )
            },
            onUsePasscode = {
                showExportAuthMethodDialog = false
                if (viewModel.hasExportPasscodeConfigured()) {
                    showVerifyExportPasscodeDialog = true
                } else {
                    showSetExportPasscodeDialog = true
                }
            }
        )
    }

    if (showSetExportPasscodeDialog) {
        SetExportPasscodeDialog(
            onDismiss = { showSetExportPasscodeDialog = false },
            onSetPasscode = { passcode ->
                val result = viewModel.setExportPasscode(passcode)
                if (result.isSuccess) {
                    showSetExportPasscodeDialog = false
                    showVerifyExportPasscodeDialog = true
                }
            }
        )
    }

    if (showVerifyExportPasscodeDialog) {
        VerifyExportPasscodeDialog(
            onDismiss = { showVerifyExportPasscodeDialog = false },
            onVerify = { passcode ->
                viewModel.unlockPrivateKeyExportWithPasscode(passcode)
                showVerifyExportPasscodeDialog = false
            }
        )
    }
}

private fun authenticateThenExportPrivateKey(
    context: Context,
    viewModel: WalletViewModel,
    onFallbackToPasscode: () -> Unit
) {
    val activity = context.findFragmentActivity()
    if (activity == null) {
        onFallbackToPasscode()
        return
    }

    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val biometric = BiometricManager.from(context)
    when (biometric.canAuthenticate(allowed)) {
        BiometricManager.BIOMETRIC_SUCCESS -> {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        viewModel.showPrivateKeyExport()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        Toast.makeText(context, "Authentication cancelled", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authenticate to export private key")
                .setSubtitle("Protect your wallet backup")
                .setAllowedAuthenticators(allowed)
                .build()
            prompt.authenticate(info)
        }
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
            onFallbackToPasscode()
        }
        else -> {
            onFallbackToPasscode()
        }
    }
}

@Composable
private fun ExportAuthMethodDialog(
    onDismiss: () -> Unit,
    onUseDeviceAuth: () -> Unit,
    onUsePasscode: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Authenticate Export",
                fontFamily = SatoshiFamily,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                "Choose how to unlock private key export.",
                fontFamily = SatoshiFamily,
                color = BitchatColors.TextSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onUseDeviceAuth) {
                Text("Device Security", fontFamily = SatoshiFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onUsePasscode) {
                Text("App Passcode", fontFamily = SatoshiFamily)
            }
        }
    )
}

@Composable
private fun SetExportPasscodeDialog(
    onDismiss: () -> Unit,
    onSetPasscode: (String) -> Unit
) {
    var passcode by remember { mutableStateOf("") }
    var confirmPasscode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Set App Passcode",
                fontFamily = SatoshiFamily,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Create a passcode for private key export fallback.",
                    fontFamily = SatoshiFamily,
                    color = BitchatColors.TextSecondary
                )
                OutlinedTextField(
                    value = passcode,
                    onValueChange = { passcode = it },
                    label = { Text("Passcode", fontFamily = SatoshiFamily) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = confirmPasscode,
                    onValueChange = { confirmPasscode = it },
                    label = { Text("Confirm Passcode", fontFamily = SatoshiFamily) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (passcode == confirmPasscode && passcode.isNotBlank()) {
                        onSetPasscode(passcode)
                    }
                },
                enabled = passcode.isNotBlank() && confirmPasscode.isNotBlank() && passcode == confirmPasscode
            ) {
                Text("Save", fontFamily = SatoshiFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = SatoshiFamily)
            }
        }
    )
}

@Composable
private fun VerifyExportPasscodeDialog(
    onDismiss: () -> Unit,
    onVerify: (String) -> Unit
) {
    var passcode by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Enter App Passcode",
                fontFamily = SatoshiFamily,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            OutlinedTextField(
                value = passcode,
                onValueChange = { passcode = it },
                label = { Text("Passcode", fontFamily = SatoshiFamily) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation()
            )
        },
        confirmButton = {
            TextButton(onClick = { onVerify(passcode) }, enabled = passcode.isNotBlank()) {
                Text("Unlock", fontFamily = SatoshiFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = SatoshiFamily)
            }
        }
    )
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun NoWalletContent(
    onCreateWallet: () -> Unit,
    onRestoreWallet: () -> Unit,
    onImportPrivateKey: () -> Unit,
    initializationIssue: String?
) {
    Spacer(modifier = Modifier.height(48.dp))

    Text(
        text = "Solana Wallet",
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        color = BitchatColors.TextPrimary
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Create a wallet to send and receive SOL over the mesh network.",
        fontFamily = SatoshiFamily,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = BitchatColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 24.dp)
    )

    if (!initializationIssue.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = initializationIssue,
            fontFamily = SatoshiFamily,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = BitchatColors.StatusError,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onCreateWallet,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = BitchatColors.ButtonPrimaryBg,
            contentColor = Color.White
        ),
        shape = BitchatShapes.Button
    ) {
        Text("Create New Wallet", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
    }

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = onRestoreWallet,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = BitchatColors.ButtonGhostBg,
            contentColor = BitchatColors.TextPrimary
        ),
        shape = BitchatShapes.Button
    ) {
        Text("Restore from Recovery Phrase", fontFamily = SatoshiFamily)
    }

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = onImportPrivateKey,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = BitchatColors.ButtonGhostBg,
            contentColor = BitchatColors.TextPrimary
        ),
        shape = BitchatShapes.Button
    ) {
        Text("Import Private Key", fontFamily = SatoshiFamily)
    }
}

@Composable
private fun WalletReadyContent(
    state: WalletUiState.Ready,
    wallets: List<WalletEntity>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onCreateMnemonicWallet: () -> Unit,
    onSwitchWallet: (String) -> Unit,
    onImportPrivateKey: () -> Unit,
    onExportPrivateKey: () -> Unit,
    onExportRecoveryPhrase: () -> Unit,
    onSend: () -> Unit = {},
    onTransactionHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    var showQr by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateMnemonicConfirm by remember { mutableStateOf(false) }
    var showSwitchWalletDialog by remember { mutableStateOf(false) }
    val canCreateMnemonicWallet = state.label.equals("Identity-Derived Wallet", ignoreCase = true)

    Spacer(modifier = Modifier.height(18.dp))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = BitchatShapes.Card,
        color = BitchatColors.BackgroundElevated,
        border = BorderStroke(1.dp, BitchatColors.Border.copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BitchatColors.BackgroundElevated,
                            BitchatColors.SurfaceVariant.copy(alpha = 0.55f)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = BitchatColors.ButtonGhostBg
                ) {
                    Text(
                        text = state.sourceLabel ?: state.label,
                        fontFamily = SatoshiFamily,
                        fontSize = 10.sp,
                        color = BitchatColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = formatBalanceFreshnessLabel(
                        lastUpdated = state.lastUpdated,
                        viaMesh = state.lastRefreshViaMesh
                    ),
                    fontFamily = SatoshiFamily,
                    fontSize = 11.sp,
                    color = BitchatColors.TextTertiary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = state.balanceUsd ?: "$0",
                fontFamily = SatoshiFamily,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = BitchatColors.TextPrimary
            )
            Text(
                text = "${state.balanceSol} SOL",
                fontFamily = SatoshiFamily,
                fontSize = 14.sp,
                color = BitchatColors.TextSecondary
            )
            if (state.usdEstimateFromLastKnownPrice) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Using last known USD price",
                    fontFamily = SatoshiFamily,
                    fontSize = 11.sp,
                    color = BitchatColors.TextTertiary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = BitchatColors.ButtonGhostBg,
                border = BorderStroke(1.dp, BitchatColors.Border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wallet Address",
                            fontFamily = SatoshiFamily,
                            fontSize = 10.sp,
                            color = BitchatColors.TextTertiary
                        )
                        Text(
                            text = state.shortAddress,
                            fontFamily = SatoshiFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = BitchatColors.TextPrimary
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Solana Address", state.address))
                            Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                        },
                        border = BorderStroke(1.dp, BitchatColors.Border),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BitchatColors.TextPrimary)
                    ) {
                        Icon(
                            painter = rememberAppIconPainter(AppIcons.Copy),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Copy", fontFamily = SatoshiFamily, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { showQr = true },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BitchatColors.SurfaceVariant,
                contentColor = BitchatColors.TextPrimary
            )
        ) {
            Icon(painter = rememberAppIconPainter(AppIcons.Download), contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "Receive",
                fontFamily = SatoshiFamily,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }

        Button(
            onClick = onSend,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BitchatColors.SurfaceVariant,
                contentColor = BitchatColors.TextPrimary
            )
        ) {
            Icon(painter = rememberAppIconPainter(AppIcons.Send), contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "Send",
                fontFamily = SatoshiFamily,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }

        Button(
            onClick = { showSwitchWalletDialog = true },
            enabled = wallets.size > 1,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BitchatColors.SurfaceVariant,
                contentColor = BitchatColors.TextPrimary,
                disabledContainerColor = BitchatColors.BackgroundElevated,
                disabledContentColor = BitchatColors.TextDisabled
            )
        ) {
            Icon(painter = rememberAppIconPainter(AppIcons.Wallet), contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "Switch",
                fontFamily = SatoshiFamily,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    Button(
        onClick = onRefresh,
        enabled = !isRefreshing,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BitchatColors.ButtonPrimaryBg,
            contentColor = BitchatColors.ButtonPrimaryFg,
            disabledContainerColor = BitchatColors.ButtonDisabledBg,
            disabledContentColor = BitchatColors.TextDisabled
        )
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = BitchatColors.ButtonPrimaryFg
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else {
            Icon(
                painter = rememberAppIconPainter(AppIcons.Sync),
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            if (isRefreshing) "Refreshing Balance..." else "Refresh Balance",
            fontFamily = SatoshiFamily
        )
    }

    // QR modal (shown when Receive tapped)
    if (showQr) {
        ReceiveQrDialog(
            address = state.address,
            onDismiss = { showQr = false }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    WalletMenuItem(
        title = "Transaction History",
        subtitle = "View all wallet transactions",
        onClick = onTransactionHistory,
        trailing = {
            Icon(
                painter = rememberAppIconPainter(AppIcons.ArrowRight),
                contentDescription = null,
                tint = BitchatColors.TextDisabled,
                modifier = Modifier.size(24.dp)
            )
        }
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Wallet Tools",
        fontFamily = SatoshiFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = BitchatColors.TextTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )

    if (canCreateMnemonicWallet) {
        WalletMenuItem(
            title = "Create Mnemonic Wallet",
            subtitle = "Replace with a new 24-word wallet",
            onClick = { showCreateMnemonicConfirm = true },
            trailing = {
                Icon(
                    painter = rememberAppIconPainter(AppIcons.ArrowRight),
                    contentDescription = null,
                    tint = BitchatColors.TextDisabled,
                    modifier = Modifier.size(24.dp)
                )
            }
        )
    }

    WalletMenuItem(
        title = "Import Private Key",
        subtitle = "Activate wallet from raw Base58 key",
        onClick = onImportPrivateKey,
        trailing = {
            Icon(
                painter = rememberAppIconPainter(AppIcons.ArrowRight),
                contentDescription = null,
                tint = BitchatColors.TextDisabled,
                modifier = Modifier.size(24.dp)
            )
        }
    )

    WalletMenuItem(
        title = "Export Private Key",
        subtitle = "Raw Base58 private key",
        onClick = onExportPrivateKey,
        trailing = {
            Icon(
                painter = rememberAppIconPainter(AppIcons.ArrowRight),
                contentDescription = null,
                tint = BitchatColors.TextDisabled,
                modifier = Modifier.size(24.dp)
            )
        }
    )

    WalletMenuItem(
        title = "Export Recovery Phrase",
        subtitle = "Backup mnemonic phrase",
        onClick = onExportRecoveryPhrase,
        trailing = {
            Icon(
                painter = rememberAppIconPainter(AppIcons.ArrowRight),
                contentDescription = null,
                tint = BitchatColors.TextDisabled,
                modifier = Modifier.size(24.dp)
            )
        }
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Delete confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    "Delete Wallet?",
                    fontFamily = SatoshiFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This will permanently delete your wallet. Make sure you have backed up your recovery phrase. This action cannot be undone.",
                    fontFamily = SatoshiFamily,
                    color = BitchatColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = BitchatColors.Destructive
                    )
                ) {
                    Text("Delete", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", fontFamily = SatoshiFamily)
                }
            }
        )
    }

    if (canCreateMnemonicWallet && showCreateMnemonicConfirm) {
        AlertDialog(
            onDismissRequest = { showCreateMnemonicConfirm = false },
            title = {
                Text(
                    "Replace Wallet?",
                    fontFamily = SatoshiFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This creates a new mnemonic wallet and makes it active. Your other wallets stay saved and can be switched later.",
                    fontFamily = SatoshiFamily,
                    color = BitchatColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreateMnemonicConfirm = false
                        onCreateMnemonicWallet()
                    }
                ) {
                    Text("Create", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateMnemonicConfirm = false }) {
                    Text("Cancel", fontFamily = SatoshiFamily)
                }
            }
        )
    }

    if (showSwitchWalletDialog) {
        AlertDialog(
            onDismissRequest = { showSwitchWalletDialog = false },
            title = {
                Text(
                    "Switch Wallet",
                    fontFamily = SatoshiFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Current active wallet is highlighted below.",
                        fontFamily = SatoshiFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = BitchatColors.TextSecondary
                    )
                    wallets.forEach { wallet ->
                        val isActive = wallet.publicKey == state.address
                        val short = if (wallet.publicKey.length > 12) {
                            "${wallet.publicKey.take(6)}...${wallet.publicKey.takeLast(4)}"
                        } else {
                            wallet.publicKey
                        }
                        OutlinedButton(
                            onClick = {
                                showSwitchWalletDialog = false
                                onSwitchWallet(wallet.publicKey)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isActive,
                            shape = BitchatShapes.Button,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isActive) BitchatColors.AccentGreen.copy(alpha = 0.7f) else BitchatColors.Border
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isActive) BitchatColors.AccentGreen.copy(alpha = 0.12f) else Color.Transparent,
                                contentColor = if (isActive) BitchatColors.AccentGreen else BitchatColors.TextPrimary,
                                disabledContainerColor = if (isActive) BitchatColors.AccentGreen.copy(alpha = 0.12f) else Color.Transparent,
                                disabledContentColor = if (isActive) BitchatColors.AccentGreen else BitchatColors.TextDisabled
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = short, fontFamily = SatoshiFamily)
                                if (isActive) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = rememberAppIconPainter(AppIcons.Check),
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "ACTIVE",
                                            fontFamily = SatoshiFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSwitchWalletDialog = false }) {
                    Text("Close", fontFamily = SatoshiFamily)
                }
            }
        )
    }
}

@Composable
private fun WalletMenuItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = SatoshiFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BitchatColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontFamily = SatoshiFamily,
                fontSize = 12.sp,
                color = BitchatColors.TextSecondary
            )
        }
        trailing()
    }
}

@Composable
private fun ReceiveQrDialog(
    address: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = BitchatShapes.Card,
            colors = CardDefaults.cardColors(containerColor = BitchatColors.BackgroundElevated),
            border = BorderStroke(1.dp, BitchatColors.Border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Receive SOL",
                    fontFamily = SatoshiFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = BitchatColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                val bitmap = remember(address) { generateQrCode(address) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Wallet QR Code",
                        modifier = Modifier
                            .size(220.dp)
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = address,
                        fontFamily = SatoshiFamily,
                        fontSize = 11.sp,
                        color = BitchatColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Solana Address", address))
                            Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            painter = rememberAppIconPainter(AppIcons.Copy),
                            contentDescription = "Copy address",
                            tint = BitchatColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = onDismiss) {
                    Text("Close", fontFamily = SatoshiFamily)
                }
            }
        }
    }
}

@Composable
private fun PrivateKeyExportDialog(
    privateKeyBase58: String,
    onDismiss: () -> Unit
) {
    var revealed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Dialog(onDismissRequest = {}) {
        Card(
            shape = BitchatShapes.Card,
            colors = CardDefaults.cardColors(containerColor = BitchatColors.BackgroundElevated),
            border = BorderStroke(1.dp, BitchatColors.StatusError.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Export Private Key",
                    fontFamily = SatoshiFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BitchatColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Anyone with this key can spend your funds. Keep it offline and private.",
                    fontFamily = SatoshiFamily,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = BitchatColors.StatusError
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (revealed) {
                    Text(
                        text = privateKeyBase58,
                        fontFamily = SatoshiFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = BitchatColors.TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BitchatColors.SurfaceVariant, BitchatShapes.Large)
                            .padding(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Solana Private Key (Base58)", privateKeyBase58))
                            Toast.makeText(context, "Private key copied - clear clipboard soon!", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BitchatColors.ButtonGhostBg,
                            contentColor = BitchatColors.TextPrimary
                        ),
                        shape = BitchatShapes.Button
                    ) {
                        Icon(painter = rememberAppIconPainter(AppIcons.Copy), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Private Key", fontFamily = SatoshiFamily)
                    }
                } else {
                    Button(
                        onClick = { revealed = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BitchatColors.ButtonGhostBg,
                            contentColor = BitchatColors.TextPrimary
                        ),
                        shape = BitchatShapes.Button
                    ) {
                        Icon(painter = rememberAppIconPainter(AppIcons.Visibility), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reveal Private Key", fontFamily = SatoshiFamily)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.ButtonPrimaryBg,
                        contentColor = Color.White
                    ),
                    shape = BitchatShapes.Button
                ) {
                    Text("Close", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun MnemonicBackupDialog(
    mnemonic: String,
    onDismiss: () -> Unit
) {
    var revealed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Dialog(onDismissRequest = {}) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(min = 320.dp, max = 460.dp),
            shape = BitchatShapes.Card,
            colors = CardDefaults.cardColors(
                containerColor = BitchatColors.BackgroundElevated
            ),
            border = BorderStroke(1.dp, BitchatColors.ButtonPrimaryBg.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = rememberAppIconPainter(AppIcons.Warning),
                        contentDescription = null,
                        tint = BitchatColors.StatusWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Backup Recovery Phrase",
                        fontFamily = SatoshiFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = BitchatColors.TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = BitchatShapes.Button,
                    color = BitchatColors.StatusError.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, BitchatColors.StatusError.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Write down these 24 words in order. This is the only way to recover your wallet.",
                        fontFamily = SatoshiFamily,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = BitchatColors.StatusError,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (revealed) {
                    val words = mnemonic.split(" ")
                    Surface(
                        shape = BitchatShapes.Large,
                        color = BitchatColors.SurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            words.chunked(2).forEachIndexed { rowIndex, row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEachIndexed { colIndex, word ->
                                        val index = rowIndex * 2 + colIndex + 1
                                        Text(
                                            text = "$index. $word",
                                            fontFamily = SatoshiFamily,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = BitchatColors.TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(2 - row.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Recovery Phrase", mnemonic))
                            Toast.makeText(context, "Phrase copied - clear clipboard soon!", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, BitchatColors.Border),
                        shape = BitchatShapes.Button,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BitchatColors.TextPrimary)
                    ) {
                        Icon(
                            painter = rememberAppIconPainter(AppIcons.Copy),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy phrase", fontFamily = SatoshiFamily)
                    }
                } else {
                    Button(
                        onClick = { revealed = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BitchatColors.ButtonGhostBg,
                            contentColor = BitchatColors.TextPrimary
                        ),
                        shape = BitchatShapes.Button
                    ) {
                        Icon(
                            painter = rememberAppIconPainter(AppIcons.Visibility),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Reveal Recovery Phrase",
                            fontFamily = SatoshiFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Never share this phrase with anyone. Store it offline.",
                    fontFamily = SatoshiFamily,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = BitchatColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    enabled = revealed,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.ButtonPrimaryBg,
                        contentColor = Color.White,
                        disabledContainerColor = BitchatColors.ButtonDisabledBg,
                        disabledContentColor = BitchatColors.TextDisabled
                    ),
                    shape = BitchatShapes.Button
                ) {
                    Text(
                        "I Saved My Phrase",
                        fontFamily = SatoshiFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreWalletDialog(
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var phrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Restore Wallet",
                fontFamily = SatoshiFamily,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter your 24-word recovery phrase:",
                    fontFamily = SatoshiFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitchatColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    placeholder = {
                        Text(
                            "word1 word2 word3 ...",
                            fontFamily = SatoshiFamily,
                            color = BitchatColors.TextDisabled
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = SatoshiFamily
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRestore(phrase) },
                enabled = phrase.trim().split("\\s+".toRegex()).size >= 12
            ) {
                Text("Restore", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = SatoshiFamily)
            }
        }
    )
}

@Composable
private fun ImportPrivateKeyDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var privateKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Import Private Key",
                fontFamily = SatoshiFamily,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Paste raw Base58 private key (32-byte Ed25519 key):",
                    fontFamily = SatoshiFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitchatColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    placeholder = {
                        Text(
                            "Base58 private key",
                            fontFamily = SatoshiFamily,
                            color = BitchatColors.TextDisabled
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = SatoshiFamily
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(privateKey) },
                enabled = privateKey.length >= 32
            ) {
                Text("Import", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = SatoshiFamily)
            }
        }
    )
}

private fun generateQrCode(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val size = 512
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

private fun formatBalanceFreshnessLabel(lastUpdated: Long, viaMesh: Boolean): String {
    if (lastUpdated <= 0L) {
        return if (viaMesh) "updated via mesh" else "not synced yet"
    }
    val relative = DateUtils.getRelativeTimeSpanString(
        lastUpdated,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
    return if (viaMesh) "updated $relative via mesh" else "updated $relative"
}

/**
 * Full-screen Transaction History screen.
 * Shows list of all transactions from Room with status, amount, and signature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionHistoryScreen(
    transactions: List<QueuedTransactionEntity>,
    activeWalletAddress: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Transactions",
                        fontFamily = SatoshiFamily,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = rememberAppIconPainter(AppIcons.ArrowBack), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BitchatColors.Background,
                    titleContentColor = BitchatColors.TextPrimary,
                    navigationIconContentColor = BitchatColors.TextPrimary
                )
            )
        },
        containerColor = BitchatColors.Background
    ) { innerPadding ->
        if (transactions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No transactions yet",
                    fontFamily = SatoshiFamily,
                    fontSize = 16.sp,
                    color = BitchatColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Transactions you send will appear here",
                    fontFamily = SatoshiFamily,
                    fontSize = 13.sp,
                    color = BitchatColors.TextTertiary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transactions, key = { it.id }) { tx ->
                    TransactionRow(
                        tx = tx,
                        activeWalletAddress = activeWalletAddress,
                        dateFormat = dateFormat,
                        onCopySignature = { sig ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Tx Signature", sig))
                            Toast.makeText(context, "Signature copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(
    tx: QueuedTransactionEntity,
    activeWalletAddress: String?,
    dateFormat: SimpleDateFormat,
    onCopySignature: (String) -> Unit
) {
    val status = TransactionStatus.fromString(tx.status)
    val statusColor = when (status) {
        TransactionStatus.CONFIRMED -> BitchatColors.StatusSuccess
        TransactionStatus.FAILED -> BitchatColors.StatusError
        TransactionStatus.BROADCASTING -> BitchatColors.ButtonPrimaryBg
        TransactionStatus.QUEUED -> BitchatColors.TextSecondary
        TransactionStatus.AWAITING_BLOCKHASH -> BitchatColors.ButtonPrimaryBg
    }
    val statusLabel = when (status) {
        TransactionStatus.CONFIRMED -> "Confirmed"
        TransactionStatus.FAILED -> "Failed"
        TransactionStatus.BROADCASTING -> "Broadcasting"
        TransactionStatus.QUEUED -> "Queued"
        TransactionStatus.AWAITING_BLOCKHASH -> "Awaiting Blockhash"
    }
    val solAmount = tx.amountLamports.toDouble() / 1_000_000_000.0
    val direction = when {
        activeWalletAddress.isNullOrBlank() -> TxDirection.OUTGOING
        tx.recipientPublicKey == activeWalletAddress -> TxDirection.INCOMING
        tx.senderPublicKey == activeWalletAddress -> TxDirection.OUTGOING
        else -> TxDirection.OUTGOING
    }
    val amountPrefix = if (direction == TxDirection.INCOMING) "+" else "-"
    val amountColor = if (direction == TxDirection.INCOMING) BitchatColors.StatusSuccess else BitchatColors.SelfMessage
    val counterpartyLabel = if (direction == TxDirection.INCOMING) "From" else "To"
    val counterpartyKey = if (direction == TxDirection.INCOMING) tx.senderPublicKey else tx.recipientPublicKey
    val shortCounterparty = if (counterpartyKey.length > 12) {
        "${counterpartyKey.take(6)}...${counterpartyKey.takeLast(4)}"
    } else counterpartyKey

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = BitchatShapes.Card,
        color = BitchatColors.BackgroundElevated,
        border = BorderStroke(1.dp, BitchatColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Top row: amount + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$amountPrefix${"%.4f".format(solAmount)} SOL",
                    fontFamily = SatoshiFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                Text(
                    text = statusLabel,
                    fontFamily = SatoshiFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Recipient
            Text(
                text = "$counterpartyLabel: $shortCounterparty",
                fontFamily = SatoshiFamily,
                fontSize = 13.sp,
                color = BitchatColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Date
            Text(
                text = dateFormat.format(Date(tx.createdAt)),
                fontFamily = SatoshiFamily,
                fontSize = 11.sp,
                color = BitchatColors.TextTertiary
            )

            // Tx signature (if confirmed)
            if (tx.txSignature != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopySignature(tx.txSignature!!) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sig: ${tx.txSignature!!.take(16)}...",
                        fontFamily = SatoshiFamily,
                        fontSize = 11.sp,
                        color = BitchatColors.ButtonPrimaryBg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = rememberAppIconPainter(AppIcons.Copy),
                        contentDescription = "Copy signature",
                        tint = BitchatColors.ButtonPrimaryBg,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Error message (if failed)
            if (status == TransactionStatus.FAILED && tx.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tx.errorMessage!!,
                    fontFamily = SatoshiFamily,
                    fontSize = 11.sp,
                    color = BitchatColors.StatusError,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class TxDirection {
    INCOMING,
    OUTGOING
}

/**
 * Full-screen Send SOL screen.
 * Shows address input with QR scan button and list of known peers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendScreen(
    onBack: () -> Unit,
    getPeersWithSolana: () -> List<Pair<String, String>>,
    isSending: Boolean,
    onSend: (address: String, amount: Double) -> Unit
) {
    var addressInput by remember { mutableStateOf("") }
    var showAmountInput by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf("") }
    var selectedNickname by remember { mutableStateOf<String?>(null) }
    var amountInput by remember { mutableStateOf("") }
    val peers = remember { getPeersWithSolana() }

    // If showing amount input step
    if (showAmountInput) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Amount",
                            fontFamily = SatoshiFamily,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { showAmountInput = false }) {
                            Icon(painter = rememberAppIconPainter(AppIcons.ArrowBack), contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BitchatColors.Background,
                        titleContentColor = BitchatColors.TextPrimary,
                        navigationIconContentColor = BitchatColors.TextPrimary
                    )
                )
            },
            containerColor = BitchatColors.Background
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Show who we're sending to
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = BitchatShapes.Card,
                    color = BitchatColors.BackgroundElevated,
                    border = BorderStroke(1.dp, BitchatColors.Border)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "To",
                            fontFamily = SatoshiFamily,
                            fontSize = 12.sp,
                            color = BitchatColors.TextTertiary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (selectedNickname != null) {
                            Text(
                                text = "@${selectedNickname}",
                                fontFamily = SatoshiFamily,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = BitchatColors.SelfMessage
                            )
                        }
                        Text(
                            text = selectedAddress,
                            fontFamily = SatoshiFamily,
                            fontSize = 12.sp,
                            color = BitchatColors.TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Amount input
                Text(
                    text = "Amount (SOL)",
                    fontFamily = SatoshiFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = BitchatColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { newVal ->
                        // Only allow valid decimal numbers
                        if (newVal.isEmpty() || newVal.matches(Regex("^\\d*\\.?\\d*$"))) {
                            amountInput = newVal
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "0.0",
                            fontFamily = SatoshiFamily,
                            color = BitchatColors.TextDisabled
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = SatoshiFamily,
                        fontSize = 20.sp
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BitchatColors.ButtonPrimaryBg,
                        unfocusedBorderColor = BitchatColors.Border,
                        cursorColor = BitchatColors.ButtonPrimaryBg,
                        focusedTextColor = BitchatColors.TextPrimary,
                        unfocusedTextColor = BitchatColors.TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Send button
                val amount = amountInput.toDoubleOrNull()
                val canSend = amount != null && amount > 0 && !isSending

                Button(
                    onClick = {
                        if (canSend) onSend(selectedAddress, amount!!)
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.ButtonPrimaryBg,
                        contentColor = Color.White,
                        disabledContainerColor = BitchatColors.ButtonDisabledBg,
                        disabledContentColor = BitchatColors.ButtonDisabledFg
                    ),
                    shape = BitchatShapes.Button
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sending...", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
                    } else {
                        Text("Send SOL", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        return
    }

    // Main send screen — address input + peer list
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Send",
                        fontFamily = SatoshiFamily,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = rememberAppIconPainter(AppIcons.ArrowBack), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BitchatColors.Background,
                    titleContentColor = BitchatColors.TextPrimary,
                    navigationIconContentColor = BitchatColors.TextPrimary
                )
            )
        },
        containerColor = BitchatColors.Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Send card with address input + peer list
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = BitchatShapes.Card,
                color = BitchatColors.BackgroundElevated,
                border = BorderStroke(1.dp, BitchatColors.Border)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Send",
                        fontFamily = SatoshiFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = BitchatColors.TextPrimary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Address input with QR scan icon
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "Solana address",
                                fontFamily = SatoshiFamily,
                                color = BitchatColors.TextDisabled,
                                fontSize = 13.sp
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = SatoshiFamily
                        ),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    // QR scan placeholder — navigate to amount if address pasted
                                    if (addressInput.isNotBlank()) {
                                        selectedAddress = addressInput.trim()
                                        selectedNickname = null
                                        showAmountInput = true
                                    }
                                }
                            ) {
                                Icon(
                                    painter = rememberAppIconPainter(AppIcons.QrCode),
                                    contentDescription = "Scan QR",
                                    tint = BitchatColors.TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BitchatColors.ButtonPrimaryBg,
                            unfocusedBorderColor = BitchatColors.Border,
                            cursorColor = BitchatColors.ButtonPrimaryBg,
                            focusedTextColor = BitchatColors.TextPrimary,
                            unfocusedTextColor = BitchatColors.TextPrimary
                        )
                    )

                    // Go button for manual address entry
                    if (addressInput.length >= 32) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                selectedAddress = addressInput.trim()
                                selectedNickname = null
                                showAmountInput = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BitchatColors.ButtonPrimaryBg,
                                contentColor = Color.White
                            ),
                            shape = BitchatShapes.Button
                        ) {
                            Text("Continue", fontFamily = SatoshiFamily, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Peer list with Solana addresses
                    if (peers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))

                        peers.forEach { (nickname, solAddress) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedAddress = solAddress
                                        selectedNickname = nickname
                                        showAmountInput = true
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "@$nickname",
                                    fontFamily = SatoshiFamily,
                                    fontSize = 14.sp,
                                    color = BitchatColors.TextPrimary
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No peers with Solana addresses found.\nEnter an address above or connect to peers.",
                            fontFamily = SatoshiFamily,
                            fontSize = 12.sp,
                            color = BitchatColors.TextTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
