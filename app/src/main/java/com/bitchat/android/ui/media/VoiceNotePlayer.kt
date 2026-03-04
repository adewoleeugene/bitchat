package com.bitchat.android.ui.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import com.bitchat.android.ui.theme.AppIcons
import com.bitchat.android.ui.theme.rememberAppIconPainter
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.SatoshiFamily

@Composable
fun VoiceNotePlayer(
    path: String,
    progressOverride: Float? = null,
    progressColor: Color? = null
) {
    val tag = "VoiceNotePlayer"
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var durationMs by remember { mutableStateOf(0) }
    val player = remember { MediaPlayer() }

    // Seek function - position is a fraction from 0.0 to 1.0
    val seekTo: (Float) -> Unit = { position ->
        if (isPrepared && durationMs > 0) {
            val seekMs = (position * durationMs).toInt().coerceIn(0, durationMs)
            try {
                player.seekTo(seekMs)
                progress = position  // Update progress immediately for UI responsiveness
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(path) {
        isPrepared = false
        isError = false
        progress = 0f
        durationMs = 0
        isPlaying = false
        try {
            player.reset()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setVolume(1f, 1f)
            player.setOnPreparedListener {
                isPrepared = true
                durationMs = try { player.duration } catch (_: Exception) { 0 }
            }
            player.setOnCompletionListener {
                isPlaying = false
                progress = 1f
            }
            player.setOnErrorListener { _, what, extra ->
                isError = true
                isPlaying = false
                Log.w(tag, "MediaPlayer error what=$what extra=$extra path=$path")
                true
            }
            player.setDataSource(path)
            player.prepareAsync()
        } catch (e: Exception) {
            isError = true
            Log.w(tag, "Failed to prepare voice note path=$path: ${e.message}")
        }
    }

    LaunchedEffect(isPlaying, isPrepared) {
        try {
            if (isPlaying && isPrepared) player.start() else if (isPrepared && player.isPlaying) player.pause()
        } catch (_: Exception) {}
    }
    LaunchedEffect(isPlaying, isPrepared) {
        while (isPlaying && isPrepared) {
            progress = try { player.currentPosition.toFloat() / (player.duration.toFloat().coerceAtLeast(1f)) } catch (_: Exception) { 0f }
            kotlinx.coroutines.delay(100)
        }
    }
    DisposableEffect(Unit) { onDispose { try { player.release() } catch (_: Exception) {} } }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Disable play/pause while showing send progress override (optional UX choice)
        val controlsEnabled = isPrepared && !isError && progressOverride == null
        FilledTonalIconButton(onClick = {
            if (controlsEnabled) {
                if (!isPlaying && progress >= 0.999f && durationMs > 0) {
                    seekTo(0f)
                }
                isPlaying = !isPlaying
            }
        }, enabled = controlsEnabled, modifier = Modifier.size(28.dp)) {
            Icon(
                painter = rememberAppIconPainter(if (isPlaying) AppIcons.Pause else AppIcons.Play),
                contentDescription = if (isPlaying) "Pause" else "Play"
            )
        }
        val progressBarColor = progressColor ?: MaterialTheme.colorScheme.primary
        com.bitchat.android.ui.media.WaveformPreview(
            modifier = Modifier
                .height(24.dp)
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            path = path,
            sendProgress = progressOverride,
            playbackProgress = if (progressOverride == null) progress else null,
            onSeek = seekTo
        )
        val durText = if (durationMs > 0) String.format("%02d:%02d", (durationMs / 1000) / 60, (durationMs / 1000) % 60) else "--:--"
        Text(text = durText, fontFamily = SatoshiFamily, fontSize = 12.sp)
    }
}
