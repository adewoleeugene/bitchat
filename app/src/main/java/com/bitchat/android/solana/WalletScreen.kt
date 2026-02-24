package com.bitchat.android.solana

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
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
import com.bitchat.android.ui.theme.PixelIcons
import com.bitchat.android.ui.theme.rememberPixelPainter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.BitchatShapes
import com.bitchat.android.ui.theme.CourierPrimeFamily
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
    val context = LocalContext.current

    // Transaction history screen (full screen overlay)
    if (showTxHistory) {
        TransactionHistoryScreen(
            transactions = transactions,
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
                        fontFamily = CourierPrimeFamily,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = rememberPixelPainter(PixelIcons.ArrowBack), contentDescription = "Back")
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
                    CircularProgressIndicator(color = BitchatColors.SolanaAccent)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Loading wallet...",
                        fontFamily = CourierPrimeFamily,
                        color = BitchatColors.TextSecondary
                    )
                }

                is WalletUiState.NoWallet -> {
                    NoWalletContent(
                        onCreateWallet = { viewModel.createWallet() },
                        onRestoreWallet = { viewModel.showRestoreDialog() }
                    )
                }

                is WalletUiState.Ready -> {
                    val state = walletState as WalletUiState.Ready
                    WalletReadyContent(
                        state = state,
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refreshBalance() },
                        onDelete = { viewModel.deleteWallet() },
                        onCreateMnemonicWallet = { viewModel.createWallet() },
                        onImportPrivateKey = { viewModel.showImportPrivateKeyDialog() },
                        onExportPrivateKey = { viewModel.showPrivateKeyExport() },
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
                    fontFamily = CourierPrimeFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    errorMessage!!,
                    fontFamily = CourierPrimeFamily,
                    color = BitchatColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text("OK", fontFamily = CourierPrimeFamily)
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
}

@Composable
private fun NoWalletContent(
    onCreateWallet: () -> Unit,
    onRestoreWallet: () -> Unit
) {
    Spacer(modifier = Modifier.height(48.dp))

    Text(
        text = "Solana Wallet",
        fontFamily = CourierPrimeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        color = BitchatColors.TextPrimary
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Create a wallet to send and receive SOL over the mesh network.",
        fontFamily = CourierPrimeFamily,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = BitchatColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 24.dp)
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onCreateWallet,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = BitchatColors.SolanaAccent,
            contentColor = Color.White
        ),
        shape = BitchatShapes.Button
    ) {
        Text("Create New Wallet", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
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
        Text("Restore from Recovery Phrase", fontFamily = CourierPrimeFamily)
    }
}

@Composable
private fun WalletReadyContent(
    state: WalletUiState.Ready,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onCreateMnemonicWallet: () -> Unit,
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
    var autoPaymentsEnabled by remember { mutableStateOf(true) }

    Spacer(modifier = Modifier.height(24.dp))

    // Balance card — minimal bordered style matching screenshot
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = BitchatShapes.Card,
        color = BitchatColors.Background,
        border = BorderStroke(1.dp, BitchatColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 28.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = state.balanceUsd ?: "$0",
                fontFamily = CourierPrimeFamily,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = BitchatColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "~ ${state.balanceSol}sol",
                fontFamily = CourierPrimeFamily,
                fontSize = 14.sp,
                color = BitchatColors.TextSecondary
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Receive / Send buttons row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = { showQr = !showQr },
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, BitchatColors.Border),
            shape = BitchatShapes.Button,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = BitchatColors.TextPrimary
            )
        ) {
            Text("Receive", fontFamily = CourierPrimeFamily)
        }

        OutlinedButton(
            onClick = onSend,
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, BitchatColors.Border),
            shape = BitchatShapes.Button,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = BitchatColors.TextPrimary
            )
        ) {
            Text("Send", fontFamily = CourierPrimeFamily)
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Refresh Balance button
    OutlinedButton(
        onClick = onRefresh,
        enabled = !isRefreshing,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        border = BorderStroke(1.dp, BitchatColors.Border),
        shape = BitchatShapes.Button,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = BitchatColors.TextPrimary,
            disabledContentColor = BitchatColors.TextDisabled
        )
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = BitchatColors.TextSecondary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            if (isRefreshing) "Refreshing..." else "Refresh Balance",
            fontFamily = CourierPrimeFamily
        )
    }

    // QR Code inline (shown when Receive tapped)
    if (showQr) {
        Spacer(modifier = Modifier.height(16.dp))
        QrCodeCard(address = state.address)
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Divider
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = BitchatColors.Border.copy(alpha = 0.3f)
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Menu items — settings-style list
    WalletMenuItem(
        title = "Transaction History",
        subtitle = "view your transaction history",
        onClick = onTransactionHistory,
        trailing = {
            Icon(
                painter = rememberPixelPainter(PixelIcons.ArrowRight),
                contentDescription = null,
                tint = BitchatColors.TextDisabled,
                modifier = Modifier.size(24.dp)
            )
        }
    )

    WalletMenuItem(
        title = "Enable automatic payments",
        subtitle = "Auto-broadcast when internet available",
        onClick = { autoPaymentsEnabled = !autoPaymentsEnabled },
        trailing = {
            Icon(
                painter = rememberPixelPainter(if (autoPaymentsEnabled) PixelIcons.CheckboxOn else PixelIcons.CheckboxOff),
                contentDescription = null,
                tint = BitchatColors.TextDisabled,
                modifier = Modifier.size(24.dp)
            )
        }
    )

    WalletMenuItem(
        title = "Create Mnemonic Wallet",
        subtitle = "Replace with a new 24-word wallet",
        onClick = { showCreateMnemonicConfirm = true },
        trailing = {
            Icon(
                painter = rememberPixelPainter(PixelIcons.ArrowRight),
                contentDescription = null,
                tint = BitchatColors.TextDisabled,
                modifier = Modifier.size(24.dp)
            )
        }
    )

    WalletMenuItem(
        title = "Import Private Key",
        subtitle = "Activate wallet from raw Base58 key",
        onClick = onImportPrivateKey,
        trailing = {
            Icon(
                painter = rememberPixelPainter(PixelIcons.ArrowRight),
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
                painter = rememberPixelPainter(PixelIcons.ArrowRight),
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
                painter = rememberPixelPainter(PixelIcons.ArrowRight),
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
                    fontFamily = CourierPrimeFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This will permanently delete your wallet. Make sure you have backed up your recovery phrase. This action cannot be undone.",
                    fontFamily = CourierPrimeFamily,
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
                    Text("Delete", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", fontFamily = CourierPrimeFamily)
                }
            }
        )
    }

    if (showCreateMnemonicConfirm) {
        AlertDialog(
            onDismissRequest = { showCreateMnemonicConfirm = false },
            title = {
                Text(
                    "Replace Wallet?",
                    fontFamily = CourierPrimeFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This creates a new mnemonic wallet and replaces the current active wallet. Back up existing secrets first.",
                    fontFamily = CourierPrimeFamily,
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
                    Text("Create", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateMnemonicConfirm = false }) {
                    Text("Cancel", fontFamily = CourierPrimeFamily)
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
                fontFamily = CourierPrimeFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BitchatColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontFamily = CourierPrimeFamily,
                fontSize = 12.sp,
                color = BitchatColors.TextSecondary
            )
        }
        trailing()
    }
}

@Composable
private fun QrCodeCard(address: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val bitmap = remember(address) { generateQrCode(address) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Wallet QR Code",
                modifier = Modifier
                    .size(200.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = address,
            fontFamily = CourierPrimeFamily,
            fontSize = 11.sp,
            color = BitchatColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Solana Address", address))
                Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
            },
            border = BorderStroke(1.dp, BitchatColors.Border),
            shape = BitchatShapes.Button,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = BitchatColors.TextPrimary
            )
        ) {
            Icon(painter = rememberPixelPainter(PixelIcons.Copy), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy Address", fontFamily = CourierPrimeFamily)
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
                    fontFamily = CourierPrimeFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BitchatColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Anyone with this key can spend your funds. Keep it offline and private.",
                    fontFamily = CourierPrimeFamily,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = BitchatColors.StatusError
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (revealed) {
                    Text(
                        text = privateKeyBase58,
                        fontFamily = CourierPrimeFamily,
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
                        Icon(painter = rememberPixelPainter(PixelIcons.Copy), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Private Key", fontFamily = CourierPrimeFamily)
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
                        Icon(painter = rememberPixelPainter(PixelIcons.Visibility), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reveal Private Key", fontFamily = CourierPrimeFamily)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.SolanaAccent,
                        contentColor = Color.White
                    ),
                    shape = BitchatShapes.Button
                ) {
                    Text("Close", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
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
            shape = BitchatShapes.Card,
            colors = CardDefaults.cardColors(
                containerColor = BitchatColors.BackgroundElevated
            ),
            border = BorderStroke(1.dp, BitchatColors.SolanaAccent.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Backup Recovery Phrase",
                    fontFamily = CourierPrimeFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BitchatColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Write down these 24 words in order. This is the ONLY way to recover your wallet.",
                    fontFamily = CourierPrimeFamily,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = BitchatColors.StatusError
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (revealed) {
                    val words = mnemonic.split(" ")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                BitchatColors.SurfaceVariant,
                                BitchatShapes.Large
                            )
                            .padding(12.dp)
                    ) {
                        words.chunked(3).forEachIndexed { rowIndex, row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                row.forEachIndexed { colIndex, word ->
                                    val index = rowIndex * 3 + colIndex + 1
                                    Text(
                                        text = "$index. $word",
                                        fontFamily = CourierPrimeFamily,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BitchatColors.TextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(3 - row.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                            if (rowIndex < words.chunked(3).size - 1) {
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Recovery Phrase", mnemonic))
                            Toast.makeText(context, "Phrase copied - clear clipboard soon!", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BitchatColors.ButtonGhostBg,
                            contentColor = BitchatColors.TextPrimary
                        ),
                        shape = BitchatShapes.Button
                    ) {
                        Icon(painter = rememberPixelPainter(PixelIcons.Copy), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy to Clipboard", fontFamily = CourierPrimeFamily)
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
                        Icon(painter = rememberPixelPainter(PixelIcons.Visibility), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reveal Recovery Phrase", fontFamily = CourierPrimeFamily)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.SolanaAccent,
                        contentColor = Color.White
                    ),
                    shape = BitchatShapes.Button
                ) {
                    Text(
                        "I've Saved My Recovery Phrase",
                        fontFamily = CourierPrimeFamily,
                        fontWeight = FontWeight.Medium
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
                fontFamily = CourierPrimeFamily,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter your 24-word recovery phrase:",
                    fontFamily = CourierPrimeFamily,
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
                            fontFamily = CourierPrimeFamily,
                            color = BitchatColors.TextDisabled
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = CourierPrimeFamily
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRestore(phrase) },
                enabled = phrase.trim().split("\\s+".toRegex()).size >= 12
            ) {
                Text("Restore", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = CourierPrimeFamily)
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
                fontFamily = CourierPrimeFamily,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Paste raw Base58 private key (32-byte Ed25519 key):",
                    fontFamily = CourierPrimeFamily,
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
                            fontFamily = CourierPrimeFamily,
                            color = BitchatColors.TextDisabled
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = CourierPrimeFamily
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(privateKey) },
                enabled = privateKey.length >= 32
            ) {
                Text("Import", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = CourierPrimeFamily)
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

/**
 * Full-screen Transaction History screen.
 * Shows list of all transactions from Room with status, amount, and signature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionHistoryScreen(
    transactions: List<QueuedTransactionEntity>,
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
                        fontFamily = CourierPrimeFamily,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = rememberPixelPainter(PixelIcons.ArrowBack), contentDescription = "Back")
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
                    fontFamily = CourierPrimeFamily,
                    fontSize = 16.sp,
                    color = BitchatColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Transactions you send will appear here",
                    fontFamily = CourierPrimeFamily,
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
    dateFormat: SimpleDateFormat,
    onCopySignature: (String) -> Unit
) {
    val status = TransactionStatus.fromString(tx.status)
    val statusColor = when (status) {
        TransactionStatus.CONFIRMED -> BitchatColors.StatusSuccess
        TransactionStatus.FAILED -> BitchatColors.StatusError
        TransactionStatus.BROADCASTING -> BitchatColors.SolanaAccent
        TransactionStatus.QUEUED -> BitchatColors.TextSecondary
        TransactionStatus.AWAITING_BLOCKHASH -> BitchatColors.SolanaAccent
    }
    val statusLabel = when (status) {
        TransactionStatus.CONFIRMED -> "Confirmed"
        TransactionStatus.FAILED -> "Failed"
        TransactionStatus.BROADCASTING -> "Broadcasting"
        TransactionStatus.QUEUED -> "Queued"
        TransactionStatus.AWAITING_BLOCKHASH -> "Awaiting Blockhash"
    }
    val solAmount = tx.amountLamports.toDouble() / 1_000_000_000.0
    val shortRecipient = if (tx.recipientPublicKey.length > 12) {
        "${tx.recipientPublicKey.take(6)}...${tx.recipientPublicKey.takeLast(4)}"
    } else tx.recipientPublicKey

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
                    text = "-${"%.4f".format(solAmount)} SOL",
                    fontFamily = CourierPrimeFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = BitchatColors.TextPrimary
                )
                Text(
                    text = statusLabel,
                    fontFamily = CourierPrimeFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Recipient
            Text(
                text = "To: $shortRecipient",
                fontFamily = CourierPrimeFamily,
                fontSize = 13.sp,
                color = BitchatColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Date
            Text(
                text = dateFormat.format(Date(tx.createdAt)),
                fontFamily = CourierPrimeFamily,
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
                        fontFamily = CourierPrimeFamily,
                        fontSize = 11.sp,
                        color = BitchatColors.SolanaAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = rememberPixelPainter(PixelIcons.Copy),
                        contentDescription = "Copy signature",
                        tint = BitchatColors.SolanaAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Error message (if failed)
            if (status == TransactionStatus.FAILED && tx.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tx.errorMessage!!,
                    fontFamily = CourierPrimeFamily,
                    fontSize = 11.sp,
                    color = BitchatColors.StatusError,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
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
                            fontFamily = CourierPrimeFamily,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { showAmountInput = false }) {
                            Icon(painter = rememberPixelPainter(PixelIcons.ArrowBack), contentDescription = "Back")
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
                            fontFamily = CourierPrimeFamily,
                            fontSize = 12.sp,
                            color = BitchatColors.TextTertiary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (selectedNickname != null) {
                            Text(
                                text = "@${selectedNickname}",
                                fontFamily = CourierPrimeFamily,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = BitchatColors.SelfMessage
                            )
                        }
                        Text(
                            text = selectedAddress,
                            fontFamily = CourierPrimeFamily,
                            fontSize = 12.sp,
                            color = BitchatColors.TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Amount input
                Text(
                    text = "Amount (SOL)",
                    fontFamily = CourierPrimeFamily,
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
                            fontFamily = CourierPrimeFamily,
                            color = BitchatColors.TextDisabled
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = CourierPrimeFamily,
                        fontSize = 20.sp
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BitchatColors.SolanaAccent,
                        unfocusedBorderColor = BitchatColors.Border,
                        cursorColor = BitchatColors.SolanaAccent,
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
                        containerColor = BitchatColors.SolanaAccent,
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
                        Text("Sending...", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
                    } else {
                        Text("Send SOL", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
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
                        fontFamily = CourierPrimeFamily,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = rememberPixelPainter(PixelIcons.ArrowBack), contentDescription = "Back")
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
                        fontFamily = CourierPrimeFamily,
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
                                fontFamily = CourierPrimeFamily,
                                color = BitchatColors.TextDisabled,
                                fontSize = 13.sp
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = CourierPrimeFamily
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
                                    painter = rememberPixelPainter(PixelIcons.QrCode),
                                    contentDescription = "Scan QR",
                                    tint = BitchatColors.TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BitchatColors.SolanaAccent,
                            unfocusedBorderColor = BitchatColors.Border,
                            cursorColor = BitchatColors.SolanaAccent,
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
                                containerColor = BitchatColors.SolanaAccent,
                                contentColor = Color.White
                            ),
                            shape = BitchatShapes.Button
                        ) {
                            Text("Continue", fontFamily = CourierPrimeFamily, fontWeight = FontWeight.Medium)
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
                                    fontFamily = CourierPrimeFamily,
                                    fontSize = 14.sp,
                                    color = BitchatColors.TextPrimary
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No peers with Solana addresses found.\nEnter an address above or connect to peers.",
                            fontFamily = CourierPrimeFamily,
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
