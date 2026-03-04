package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.ui.theme.AppIcons
import com.bitchat.android.ui.theme.BodyFontFamily
import com.bitchat.android.ui.theme.HeadingFontFamily
import com.bitchat.android.ui.theme.rememberAppIconPainter

@Composable
fun OnboardingEssentialsScreen(
    modifier: Modifier,
    nearbyPermissionGranted: Boolean,
    locationPermissionGranted: Boolean,
    bluetoothStatus: BluetoothStatus,
    locationStatus: LocationStatus,
    notificationsSupported: Boolean,
    notificationPermissionGranted: Boolean,
    isLoading: Boolean,
    onGrantPermission: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val bluetoothReady = bluetoothStatus == BluetoothStatus.ENABLED
    val locationServicesReady = locationStatus == LocationStatus.ENABLED
    val ready = nearbyPermissionGranted &&
        locationPermissionGranted &&
        bluetoothReady &&
        locationServicesReady

    OnboardingBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Set up essentials",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = HeadingFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Required before you can continue:",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BodyFontFamily),
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            EssentialListItem(
                title = "Nearby devices permission",
                enabled = nearbyPermissionGranted
            )
            EssentialListItem(
                title = "Location permission",
                enabled = locationPermissionGranted
            )
            EssentialListItem(
                title = "Bluetooth turned on",
                enabled = bluetoothReady
            )
            EssentialListItem(
                title = "Location services turned on",
                enabled = locationServicesReady
            )
            if (notificationsSupported) {
                EssentialListItem(
                    title = "Notifications (optional)",
                    enabled = notificationPermissionGranted
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = onGrantPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
            ) {
                Text(
                    text = "Grant Permission",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = BodyFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            if (ready) {
                Text(
                    text = "All required essentials are enabled.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = BodyFontFamily),
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun EssentialListItem(
    title: String,
    enabled: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = BodyFontFamily,
                    fontWeight = FontWeight.SemiBold
                ),
                color = colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    painter = rememberAppIconPainter(if (enabled) AppIcons.Check else AppIcons.Warning),
                    contentDescription = null,
                    tint = if (enabled) colorScheme.primary else colorScheme.error
                )
                Text(
                    text = if (enabled) "On" else "Off",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = BodyFontFamily,
                        fontWeight = FontWeight.Medium
                    ),
                    color = if (enabled) colorScheme.primary else colorScheme.error
                )
            }
        }
    }
}
