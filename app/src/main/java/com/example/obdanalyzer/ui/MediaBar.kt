package com.example.obdanalyzer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.obdanalyzer.core.MediaKeys
import kotlinx.coroutines.delay

/** Volume − / précédent / lecture-pause / suivant / volume + : gros boutons faciles à toucher en conduisant. */
@Composable
fun MediaBar(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val ctx = LocalContext.current
    val st = com.example.obdanalyzer.core.ObdManager.settings.collectAsState().value
    val spotify = st.spotifyButton
    val media = st.mediaButtons
    var playing by remember { mutableStateOf(MediaKeys.isPlaying(ctx)) }
    LaunchedEffect(Unit) { while (true) { playing = MediaKeys.isPlaying(ctx); delay(1000) } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (size < 48.dp) 6.dp else 10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (spotify) FilledIconButton(onClick = { MediaKeys.openSpotify(ctx) }, Modifier.size(size),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black)) {
            Icon(Icons.Rounded.MusicNote, "Ouvrir Spotify", Modifier.size(size * 0.5f))
        }
        if (media) {
        FilledTonalIconButton(onClick = { MediaKeys.volumeDown(ctx) }, Modifier.size(size)) {
            Icon(Icons.AutoMirrored.Rounded.VolumeDown, "Baisser le volume", Modifier.size(size * 0.5f))
        }
        FilledTonalIconButton(onClick = { MediaKeys.previous(ctx) }, Modifier.size(size)) {
            Icon(Icons.Rounded.SkipPrevious, "Morceau précédent", Modifier.size(size * 0.5f))
        }
        FilledIconButton(onClick = { MediaKeys.playPause(ctx); playing = !playing }, Modifier.size(size)) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Lecture", Modifier.size(size * 0.55f))
        }
        FilledTonalIconButton(onClick = { MediaKeys.next(ctx) }, Modifier.size(size)) {
            Icon(Icons.Rounded.SkipNext, "Morceau suivant", Modifier.size(size * 0.5f))
        }
        FilledTonalIconButton(onClick = { MediaKeys.volumeUp(ctx) }, Modifier.size(size)) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Monter le volume", Modifier.size(size * 0.5f))
        }
        }
    }
}
