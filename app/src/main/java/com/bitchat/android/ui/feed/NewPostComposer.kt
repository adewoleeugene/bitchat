package com.bitchat.android.ui.feed

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.SatoshiFamily
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPostComposer(
    onPost: (content: String, imageBytes: ByteArray?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    val colorScheme = MaterialTheme.colorScheme

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val outPath = ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            if (!outPath.isNullOrBlank()) {
                imagePath = outPath
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitchatColors.Background,
        contentColor = BitchatColors.TextPrimary,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // Title
            Text(
                text = "New Post",
                style = MaterialTheme.typography.titleMedium,
                color = BitchatColors.TextPrimary,
                fontFamily = SatoshiFamily,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Text input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 200.dp)
                    .background(BitchatColors.InputFieldBg, RoundedCornerShape(8.dp))
                    .border(1.dp, BitchatColors.Border, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.text.length <= 500) text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = BitchatColors.TextPrimary,
                        fontFamily = SatoshiFamily
                    ),
                    cursorBrush = SolidColor(colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
                if (text.text.isEmpty()) {
                    Text(
                        text = "What's on your mind?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BitchatColors.TextTertiary,
                        fontFamily = SatoshiFamily
                    )
                }
            }

            // Char count
            Text(
                text = "${text.text.length}/500",
                style = MaterialTheme.typography.labelSmall,
                color = BitchatColors.TextTertiary,
                fontFamily = SatoshiFamily,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)
            )

            // Image preview
            imagePath?.let { path ->
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    val bitmap = remember(path) {
                        try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
                    }
                    bitmap?.let { bmp ->
                        val aspect = bmp.width.toFloat() / bmp.height.toFloat()
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .widthIn(max = 200.dp)
                                .aspectRatio(aspect)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    // Remove button
                    Text(
                        text = "x",
                        color = Color.White,
                        fontFamily = SatoshiFamily,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clickable { imagePath = null }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach image
                Text(
                    text = "[attach image]",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitchatColors.MeshChannel,
                    fontFamily = SatoshiFamily,
                    modifier = Modifier
                        .clickable { imagePicker.launch("image/*") }
                        .padding(8.dp)
                )

                // Post button
                val canPost = text.text.isNotBlank()
                Box(
                    modifier = Modifier
                        .background(
                            if (canPost) BitchatColors.AccentGreen else BitchatColors.BackgroundElevated,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = canPost) {
                            val imageBytes = imagePath?.let { path ->
                                try { File(path).readBytes() } catch (_: Exception) { null }
                            }
                            onPost(text.text.trim(), imageBytes)
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Post",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canPost) Color.Black else BitchatColors.TextDisabled,
                        fontFamily = SatoshiFamily
                    )
                }
            }
        }
    }
}
