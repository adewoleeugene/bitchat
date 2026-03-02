package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.bitchat.android.ui.theme.PixelIcons
import com.bitchat.android.ui.theme.rememberPixelPainter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.CourierPrimeFamily

/**
 * Permission explanation screen shown before requesting permissions
 * Explains why bitchat needs each permission and reassures users about privacy
 */
@Composable
fun PermissionExplanationScreen(
    modifier: Modifier,
    permissionCategories: List<PermissionCategory>,
    onContinue: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 88.dp) // Leave space for the fixed button
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            // Header Section - matching AboutSheet style
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = CourierPrimeFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp
                        ),
                        color = colorScheme.onBackground
                    )
                }

                Text(
                    text = stringResource(R.string.about_tagline),
                    fontSize = 12.sp,
                    fontFamily = CourierPrimeFamily,
                    color = colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            // Privacy assurance section - matching AboutSheet card style
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painter = rememberPixelPainter(PixelIcons.Shield),
                            contentDescription = stringResource(R.string.cd_privacy_protected),
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.privacy_protected),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.privacy_bullets),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = CourierPrimeFamily,
                                color = colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Section header
            Text(
                text = stringResource(R.string.permissions_header),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            // Permission categories
            permissionCategories.forEach { category ->
                PermissionCategoryCard(
                    category = category,
                    colorScheme = colorScheme
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Fixed button at bottom
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary
                )
            ) {
                Text(
                    text = stringResource(R.string.grant_permissions),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = CourierPrimeFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionCategoryCard(
    category: PermissionCategory,
    colorScheme: ColorScheme
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Icon(
            painter = rememberPixelPainter(getPermissionIcon(category.type)),
            contentDescription = category.type.nameValue,
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = category.type.nameValue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onBackground.copy(alpha = 0.8f)
            )

            if (category.type == PermissionType.PRECISE_LOCATION) {
                // Extra emphasis for location permission
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = rememberPixelPainter(PixelIcons.Warning),
                        contentDescription = stringResource(R.string.cd_warning),
                        tint = BitchatColors.StatusWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.location_tracking_warning),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = CourierPrimeFamily,
                            fontWeight = FontWeight.Medium,
                            color = BitchatColors.StatusWarning
                        )
                    )
                }
            }
        }
    }
}

private fun getPermissionIcon(permissionType: PermissionType): ImageVector {
    return when (permissionType) {
        PermissionType.NEARBY_DEVICES -> PixelIcons.Bluetooth
        PermissionType.PRECISE_LOCATION -> PixelIcons.LocationPin
        PermissionType.MICROPHONE -> PixelIcons.Mic
        PermissionType.NOTIFICATIONS -> PixelIcons.Bell
        PermissionType.BATTERY_OPTIMIZATION -> PixelIcons.Power
        PermissionType.OTHER -> PixelIcons.Settings
    }
}
