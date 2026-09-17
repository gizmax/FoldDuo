package cz.pflanzer.foldduo.desk

import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap

/**
 * B47 "Stůl": the deck's media card. The controller comes from the caller (DeskOverlay.kt passes
 * the first `IslandKind.MEDIA` island item's [MediaController], the same notification-listener
 * grant island/IslandNotificationListener.kt already turns into a controller — no separate
 * MediaSessionManager plumbing needed here) so this file stays UI-only.
 */
@Composable
internal fun DeskMediaCard(controller: MediaController?, modifier: Modifier = Modifier) {
    if (controller == null) {
        DeskEmptyMediaCard(modifier)
        return
    }
    var playback by remember(controller) { mutableStateOf(controller.playbackState) }
    var metadata by remember(controller) { mutableStateOf(controller.metadata) }
    DisposableEffect(controller) {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) { playback = state }
            override fun onMetadataChanged(meta: MediaMetadata?) { metadata = meta }
        }
        controller.registerCallback(callback)
        onDispose { controller.unregisterCallback(callback) }
    }
    val playing = playback?.state == PlaybackState.STATE_PLAYING || playback?.state == PlaybackState.STATE_BUFFERING
    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Not playing"
    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
    val art: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
    Column(modifier.testTag("desk-media-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (art != null) {
                Image(art.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
            } else {
                Box44 { Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = .8f)) }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, maxLines = 1)
                if (artist.isNotBlank()) Text(artist, color = Color.White.copy(alpha = .65f), fontSize = 12.sp, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { runCatching { controller.transportControls.skipToPrevious() } }, modifier = Modifier.testTag("desk-media-prev")) {
                Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White)
            }
            IconButton(onClick = { runCatching { if (playing) controller.transportControls.pause() else controller.transportControls.play() } },
                modifier = Modifier.testTag("desk-media-playpause")) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White)
            }
            IconButton(onClick = { runCatching { controller.transportControls.skipToNext() } }, modifier = Modifier.testTag("desk-media-next")) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White)
            }
        }
        DeskVolumeSlider()
    }
}

@Composable
private fun Box44(content: @Composable () -> Unit) {
    Row(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .12f)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun DeskEmptyMediaCard(modifier: Modifier = Modifier) {
    Column(modifier.testTag("desk-media-card-empty")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = .5f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Nothing playing", color = Color.White.copy(alpha = .65f), fontSize = 13.sp)
        }
        DeskVolumeSlider()
    }
}

/** Media-stream volume, this window's own — no `WRITE_SETTINGS` needed (matches SeamPalette's
 * per-window brightness slider, same reasoning: a normal app may drive its own stream volume). */
@Composable
private fun DeskVolumeSlider() {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    var volume by remember { mutableFloatStateOf(currentVolumeFraction(audioManager)) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeUp, "Volume", tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(16.dp))
        Slider(value = volume, onValueChange = { volume = it; setVolumeFraction(audioManager, it) },
            modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("desk-volume"),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = .8f)))
    }
}

private fun currentVolumeFraction(audioManager: AudioManager?): Float {
    val manager = audioManager ?: return 0f
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return 0f
    return manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
}

private fun setVolumeFraction(audioManager: AudioManager?, fraction: Float) {
    val manager = audioManager ?: return
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction.coerceIn(0f, 1f) * max).toInt(), 0) }
}
