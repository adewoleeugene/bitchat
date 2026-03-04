package com.bitchat.android.ui.feed

import android.graphics.BitmapFactory
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.ui.media.VoiceNotePlayer
import com.bitchat.android.ui.theme.AppIcons
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.SatoshiFamily
import com.bitchat.android.ui.theme.rememberAppIconPainter
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPostComposer(
    onPost: (content: String, imageBytes: ByteArray?, audioBytes: ByteArray?, audioPath: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxAudioDurationMs = 15_000L
    val maxAudioBytes = 128 * 1024L
    val context = LocalContext.current
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var audioPath by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartedAt by remember { mutableStateOf<Long?>(null) }
    var recordingElapsedSec by remember { mutableStateOf(0L) }
    val colorScheme = MaterialTheme.colorScheme
    val voiceRecorder = remember(context) { VoiceRecorder(context) }

    val stopRecordingAndStore: () -> Unit = {
        val out = voiceRecorder.stop()
        isRecording = false
        recordingStartedAt = null
        recordingElapsedSec = 0L
        if (out != null && out.exists() && out.length() > 0L) {
            audioPath = out.absolutePath
        }
    }

    val startRecording: () -> Unit = {
        val out = voiceRecorder.start()
        if (out != null) {
            isRecording = true
            recordingStartedAt = System.currentTimeMillis()
            recordingElapsedSec = 0L
            audioPath = null
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
    }

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

    LaunchedEffect(isRecording) {
        while (isRecording) {
            voiceRecorder.pollAmplitude()
            val startedAt = recordingStartedAt
            if (startedAt != null) {
                recordingElapsedSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
            }
            if (startedAt != null && System.currentTimeMillis() - startedAt >= maxAudioDurationMs) {
                stopRecordingAndStore()
                break
            }
            kotlinx.coroutines.delay(120L)
        }
    }

    DisposableEffect(Unit) {
        onDispose { voiceRecorder.stop() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitchatColors.BackgroundElevated,
        contentColor = BitchatColors.TextPrimary,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New Post",
                    style = MaterialTheme.typography.titleMedium,
                    color = BitchatColors.TextPrimary,
                    fontFamily = SatoshiFamily
                )
                Text(
                    text = "${text.text.length}/500",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitchatColors.TextTertiary,
                    fontFamily = SatoshiFamily
                )
            }

            // Text input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 200.dp)
                    .background(BitchatColors.InputFieldBg, RoundedCornerShape(12.dp))
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

            // Image preview
            imagePath?.let { path ->
                Spacer(modifier = Modifier.height(10.dp))
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
                                .fillMaxWidth()
                                .aspectRatio(aspect)
                                .clip(RoundedCornerShape(12.dp)),
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

            // Audio preview
            audioPath?.let { path ->
                val fileSize = try { File(path).length() } catch (_: Exception) { 0L }
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BitchatColors.InputFieldBg, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    VoiceNotePlayer(path = path)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "Remove audio",
                            color = BitchatColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = SatoshiFamily,
                            modifier = Modifier.clickable { audioPath = null }
                        )
                    }
                    if (fileSize > maxAudioBytes) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Audio is too large. Keep it shorter.",
                            color = BitchatColors.Destructive,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = SatoshiFamily
                        )
                    }
                }
            }

            if (isRecording) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Recording... ${recordingElapsedSec}s / ${maxAudioDurationMs / 1000L}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitchatColors.AccentGreen,
                    fontFamily = SatoshiFamily
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = BitchatColors.SurfaceVariant,
                        contentColor = BitchatColors.TextPrimary
                    ),
                    modifier = Modifier
                ) {
                    Icon(
                        painter = rememberAppIconPainter(AppIcons.Attachment),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Attach",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = SatoshiFamily
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isRecording) {
                                stopRecordingAndStore()
                            } else {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    startRecording()
                                } else {
                                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isRecording) {
                                BitchatColors.AccentGreen.copy(alpha = 0.16f)
                            } else {
                                BitchatColors.SurfaceVariant
                            },
                            contentColor = BitchatColors.TextPrimary
                        )
                    ) {
                        Icon(
                            painter = rememberAppIconPainter(if (isRecording) AppIcons.Close else AppIcons.Mic),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRecording) "Stop" else "Audio",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = SatoshiFamily
                        )
                    }
                }

                // Post button
                val audioTooLarge = audioPath?.let { path ->
                    try { File(path).length() > maxAudioBytes } catch (_: Exception) { false }
                } ?: false
                val canPost = (text.text.isNotBlank() || imagePath != null || audioPath != null) && !audioTooLarge
                Box(
                    modifier = Modifier
                        .background(
                            if (canPost) BitchatColors.ButtonPrimaryBg else BitchatColors.BackgroundElevated,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = canPost) {
                            val imageBytes = imagePath?.let { path ->
                                try { File(path).readBytes() } catch (_: Exception) { null }
                            }
                            val audioBytes = audioPath?.let { path ->
                                try { File(path).readBytes() } catch (_: Exception) { null }
                            }
                            onPost(text.text.trim(), imageBytes, audioBytes, audioPath)
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = rememberAppIconPainter(AppIcons.Send),
                            contentDescription = null,
                            tint = if (canPost) BitchatColors.ButtonPrimaryFg else BitchatColors.TextDisabled,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Post",
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 12.sp,
                            color = if (canPost) BitchatColors.ButtonPrimaryFg else BitchatColors.TextDisabled,
                            fontFamily = SatoshiFamily
                        )
                    }
                }
            }
        }
    }
}
