package com.bitchat.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bitchat.android.ui.theme.AppIcons
import com.bitchat.android.ui.theme.rememberAppIconPainter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.nostr.NostrProofOfWork
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.ui.debug.DebugSettingsSheet
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.BitchatShapes
import com.bitchat.android.ui.theme.CourierPrimeFamily
import com.bitchat.android.di.SolanaEntryPoint
import dagger.hilt.android.EntryPointAccessors
/**
 * About Sheet for bitchat app information
 * Matches the design language of LocationChannelsSheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onShowDebug: (() -> Unit)? = null,
    onShowWallet: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val walletSummary = remember(isPresented) {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SolanaEntryPoint::class.java
            )
            val walletService = entryPoint.solanaWalletService()
            WalletAboutSummary(
                address = walletService.getPublicKeyBase58(),
                initIssue = walletService.getInitializationIssueMessage(),
                lifecycleWarning = walletService.getLifecycleWarningMessage(),
                exportAudit = walletService.getPrivateKeyExportAuditSummary()
            )
        } catch (_: Exception) {
            WalletAboutSummary(
                address = null,
                initIssue = "Wallet service unavailable",
                lifecycleWarning = null,
                exportAudit = null
            )
        }
    }
    
    // Get version name from package info
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0" // fallback version
        }
    }
    
    // Bottom sheet state

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    val lazyListState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    // Color scheme matching LocationChannelsSheet
    val colorScheme = MaterialTheme.colorScheme
    
    if (isPresented) {
        ModalBottomSheet(
            modifier = modifier.statusBarsPadding(),
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = null
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 20.dp)
                ) {
                    // Header Section
                    item(key = "header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = TextStyle(
                                        fontFamily = CourierPrimeFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 32.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground
                                )

                                Text(
                                    text = stringResource(R.string.version_prefix, versionName?:""),
                                    fontSize = 12.sp,
                                    fontFamily = CourierPrimeFamily,
                                    color = colorScheme.onBackground.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        baselineShift = BaselineShift(0.1f)
                                    )
                                )
                            }

                            Text(
                                text = stringResource(R.string.about_tagline),
                                fontSize = 12.sp,
                                fontFamily = CourierPrimeFamily,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Features section
                    item(key = "feature_offline") {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(
                                painter = rememberAppIconPainter(AppIcons.Bluetooth),
                                contentDescription = stringResource(R.string.cd_offline_mesh_chat),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.about_offline_mesh_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.about_offline_mesh_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                    item(key = "feature_geohash") {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(
                                painter = rememberAppIconPainter(AppIcons.Globe),
                                contentDescription = stringResource(R.string.cd_online_geohash_channels),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.about_online_geohash_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.about_online_geohash_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                    item(key = "feature_encryption") {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(
                                painter = rememberAppIconPainter(AppIcons.Lock),
                                contentDescription = stringResource(R.string.cd_end_to_end_encryption),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.about_e2e_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.about_e2e_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // Proof of Work Section
                    item(key = "pow_section") {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(top = 24.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(16.dp)
                                    .background(BitchatColors.SectionAccentLine.copy(alpha = 0.5f), RoundedCornerShape(1.5.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.about_pow),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                        LaunchedEffect(Unit) {
                            PoWPreferenceManager.init(context)
                        }

                        val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
                        val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()

                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = !powEnabled,
                                    onClick = { PoWPreferenceManager.setPowEnabled(false) },
                                    label = { Text(stringResource(R.string.about_pow_off), fontFamily = CourierPrimeFamily) }
                                )
                                FilterChip(
                                    selected = powEnabled,
                                    onClick = { PoWPreferenceManager.setPowEnabled(true) },
                                    label = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(stringResource(R.string.about_pow_on), fontFamily = CourierPrimeFamily)
                                            // Show current difficulty
                                            if (powEnabled) {
                                                Surface(
                                                    color = BitchatColors.AccentGreen,
                                                    shape = RoundedCornerShape(50)
                                                ) { Box(Modifier.size(8.dp)) }
                                            }
                                        }
                                    }
                                )
                            }

                            Text(
                                text = stringResource(R.string.about_pow_tip),
                                fontSize = 12.sp,
                                fontFamily = CourierPrimeFamily,
                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                            )

                            // Show difficulty slider when enabled
                            if (powEnabled) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.about_pow_difficulty, powDifficulty, NostrProofOfWork.estimateMiningTime(powDifficulty)),
                                        fontSize = 12.sp,
                                        fontFamily = CourierPrimeFamily,
                                    )

                                    Slider(
                                        value = powDifficulty.toFloat(),
                                        onValueChange = { PoWPreferenceManager.setPowDifficulty(it.toInt()) },
                                        valueRange = 0f..32f,
                                        steps = 33,
                                        colors = SliderDefaults.colors(
                                            thumbColor = BitchatColors.AccentGreen,
                                            activeTrackColor = BitchatColors.AccentGreen
                                        )
                                    )

                                    // Show difficulty description
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = BitchatColors.BackgroundElevated.copy(alpha = 0.6f),
                                        shape = BitchatShapes.Large
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.about_pow_difficulty_attempts, powDifficulty, NostrProofOfWork.estimateWork(powDifficulty)),
                                                fontSize = 12.sp,
                                                fontFamily = CourierPrimeFamily,
                                                color = colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                            Text(
                                                text = when {
                                                    powDifficulty == 0 -> stringResource(R.string.about_pow_desc_none)
                                                    powDifficulty <= 8 -> stringResource(R.string.about_pow_desc_very_low)
                                                    powDifficulty <= 12 -> stringResource(R.string.about_pow_desc_low)
                                                    powDifficulty <= 16 -> stringResource(R.string.about_pow_desc_medium)
                                                    powDifficulty <= 20 -> stringResource(R.string.about_pow_desc_high)
                                                    powDifficulty <= 24 -> stringResource(R.string.about_pow_desc_very_high)
                                                    else -> stringResource(R.string.about_pow_desc_extreme)
                                                },
                                                fontSize = 12.sp,
                                                fontFamily = CourierPrimeFamily,
                                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Network (Tor) section
                    item(key = "network_section") {
                        val torMode = remember { mutableStateOf(com.bitchat.android.net.TorPreferenceManager.get(context)) }
                        val torStatus by com.bitchat.android.net.TorManager.statusFlow.collectAsState()
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(top = 24.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(16.dp)
                                    .background(BitchatColors.SectionAccentLine.copy(alpha = 0.5f), RoundedCornerShape(1.5.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.about_network),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                        Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = torMode.value == com.bitchat.android.net.TorMode.OFF,
                                    onClick = {
                                        torMode.value = com.bitchat.android.net.TorMode.OFF
                                        com.bitchat.android.net.TorPreferenceManager.set(context, torMode.value)
                                    },
                                    label = { Text("tor off", fontFamily = CourierPrimeFamily) }
                                )
                                FilterChip(
                                    selected = torMode.value == com.bitchat.android.net.TorMode.ON,
                                    onClick = {
                                        torMode.value = com.bitchat.android.net.TorMode.ON
                                        com.bitchat.android.net.TorPreferenceManager.set(context, torMode.value)
                                    },
                                    label = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("tor on", fontFamily = CourierPrimeFamily)
                                            val statusColor = when {
                                                torStatus.running && torStatus.bootstrapPercent < 100 -> BitchatColors.SelfMessage
                                                torStatus.running && torStatus.bootstrapPercent >= 100 -> BitchatColors.AccentGreen
                                                else -> BitchatColors.StatusError
                                            }
                                            Surface(color = statusColor, shape = CircleShape) {
                                                Box(Modifier.size(8.dp))
                                            }
                                        }
                                    }
                                )
                            }
                            Text(
                                text = stringResource(R.string.about_tor_route),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            if (torMode.value == com.bitchat.android.net.TorMode.ON) {
                                val statusText = if (torStatus.running) "Running" else "Stopped"
                                // Debug status (temporary)
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = BitchatColors.BackgroundElevated.copy(alpha = 0.6f),
                                    shape = BitchatShapes.Large
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.about_tor_status, statusText, torStatus.bootstrapPercent),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorScheme.onSurface.copy(alpha = 0.75f)
                                        )
                                        val lastLog = torStatus.lastLogLine
                                        if (lastLog.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.about_last, lastLog.take(160)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Solana Wallet Section
                    if (onShowWallet != null) {
                        item(key = "wallet_section") {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 24.dp)
                                    .padding(top = 24.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(16.dp)
                                        .background(BitchatColors.SolanaAccent.copy(alpha = 0.5f), RoundedCornerShape(1.5.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SOLANA WALLET",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .padding(horizontal = 24.dp)
                                    .fillMaxWidth()
                                    .clickable {
                                        onShowWallet()
                                        onDismiss()
                                    },
                                color = BitchatColors.BackgroundElevated,
                                shape = BitchatShapes.Card,
                                border = BorderStroke(1.dp, BitchatColors.SolanaAccent.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = rememberAppIconPainter(AppIcons.Wallet),
                                        contentDescription = "Solana Wallet",
                                        tint = BitchatColors.SolanaAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Open Wallet",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = CourierPrimeFamily,
                                            color = colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Send & receive SOL on devnet",
                                            fontSize = 12.sp,
                                            fontFamily = CourierPrimeFamily,
                                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                        walletSummary.address?.let { address ->
                                            Text(
                                                text = "Address: ${if (address.length > 12) "${address.take(6)}...${address.takeLast(4)}" else address}",
                                                fontSize = 11.sp,
                                                fontFamily = CourierPrimeFamily,
                                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        walletSummary.lifecycleWarning?.let { warning ->
                                            Text(
                                                text = warning,
                                                fontSize = 11.sp,
                                                fontFamily = CourierPrimeFamily,
                                                color = colorScheme.error.copy(alpha = 0.9f)
                                            )
                                        }
                                        walletSummary.initIssue?.let { issue ->
                                            Text(
                                                text = issue,
                                                fontSize = 11.sp,
                                                fontFamily = CourierPrimeFamily,
                                                color = colorScheme.error.copy(alpha = 0.9f)
                                            )
                                        }
                                        walletSummary.exportAudit?.let { audit ->
                                            Text(
                                                text = audit,
                                                fontSize = 11.sp,
                                                fontFamily = CourierPrimeFamily,
                                                color = colorScheme.onSurface.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Emergency Warning Section
                    item(key = "warning_section") {
                        val colorScheme = MaterialTheme.colorScheme
                        val errorColor = colorScheme.error

                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 24.dp, vertical = 24.dp)
                                .fillMaxWidth(),
                            color = errorColor.copy(alpha = 0.1f),
                            shape = BitchatShapes.Card
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    painter = rememberAppIconPainter(AppIcons.Warning),
                                    contentDescription = stringResource(R.string.cd_warning),
                                    tint = errorColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.about_emergency_title),
                                        fontSize = 12.sp,
                                        fontFamily = CourierPrimeFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = errorColor
                                    )
                                    Text(
                                        text = stringResource(R.string.about_emergency_tip),
                                        fontSize = 12.sp,
                                        fontFamily = CourierPrimeFamily,
                                        color = colorScheme.onSurface.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }

                    // Footer Section
                    item(key = "footer") {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onShowDebug != null) {
                                TextButton(
                                    onClick = onShowDebug,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.about_debug_settings),
                                        fontSize = 12.sp,
                                        fontFamily = CourierPrimeFamily
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.about_footer),
                                fontSize = 12.sp,
                                fontFamily = CourierPrimeFamily,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )

                            // Add extra space at bottom for gesture area
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }

                // TopBar
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha))
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.close_plain),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

private data class WalletAboutSummary(
    val address: String?,
    val initIssue: String?,
    val lifecycleWarning: String?,
    val exportAudit: String?
)

/**
 * Password prompt dialog for password-protected channels
 * Kept as dialog since it requires user input
 */
@Composable
fun PasswordPromptDialog(
    show: Boolean,
    channelName: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show && channelName != null) {
        val colorScheme = MaterialTheme.colorScheme
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.pwd_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.pwd_prompt_message, channelName ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.pwd_label), style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = CourierPrimeFamily
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = stringResource(R.string.join),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}
