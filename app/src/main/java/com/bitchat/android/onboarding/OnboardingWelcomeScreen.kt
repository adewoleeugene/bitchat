package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.ui.theme.BodyFontFamily
import com.bitchat.android.ui.theme.HeadingFontFamily

@Composable
fun OnboardingWelcomeScreen(
    modifier: Modifier,
    onContinue: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    OnboardingBackground(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Welcome to BitChat",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = HeadingFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Connect and chat with people nearby.",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium
                            ),
                            color = colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "No internet required.",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "To help you discover nearby devices and stay connected, we need access to Bluetooth and location services.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BodyFontFamily),
                            color = colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
                ) {
                    Text(
                        text = "Start Connecting",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = BodyFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}
