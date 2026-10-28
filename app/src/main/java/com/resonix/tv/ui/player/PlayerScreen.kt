package com.resonix.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.resonix.tv.audio.AudioPipeline
import com.resonix.tv.data.HistoryStore
import com.resonix.tv.data.SettingsStore
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.player.PlayRequest
import com.resonix.tv.player.PlayerFactory
import com.resonix.tv.ui.channelLayoutName
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.panel.AudioPanel
import com.resonix.tv.ui.panel.AudioPanelController
import com.resonix.tv.ui.panel.PanelState
import com.resonix.tv.ui.panel.PanelTab
import com.resonix.tv.ui.panel.TrackItem
import com.resonix.tv.ui.panel.buildTrackItems
import com.resonix.tv.ui.panel.codecName
import com.resonix.tv.ui.panel.selectedAudioFormat
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.theme.neuRaised
import kotlin.math.log10
import kotlinx.coroutines.delay

/** One tap = the step from Settings; while the key is held, every 5th repeat jumps three steps. */
private fun seekStep(repeat: Int, baseMs: Long): Long = when {
    repeat == 0 -> baseMs
    repeat % 5 == 0 -> baseMs * 3
    else -> 0L
}

private fun seekBy(player: Player, deltaMs: Long) {
    if (deltaMs == 0L) return
    val duration = player.duration
    val limit = if (duration == C.TIME_UNSET || duration <= 0) Long.MAX_VALUE else duration
    player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, limit))
}

/** Switches the audio track / subtitle on the fly. */
fun selectTrack(player: Player, choice: TrackItem.Choice) {
    val group = choice.group
    val builder = player.trackSelectionParameters.buildUpon()
    if (choice.isText) {
        if (group == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
        }
    } else if (group != null) {
        builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex))
    }
    player.trackSelectionParameters = builder.build()
}

/**
 * Full-screen playback with an auto-hiding OSD and the sliding audio panel.
 *   OK / Play-Pause: play or pause     LEFT / RIGHT: seek      UP: show or hide the OSD
 *   DOWN or MENU: audio panel          BACK: close panel, hide OSD, then leave
 */
@Composable
fun PlayerScreen(request: PlayRequest, onExit: () -> Unit) {
    val context = LocalContext.current
    val title = request.title
    val uriKey = request.uri.toString()
    val settings = remember { SettingsStore.get(context) }
    val history = remember { HistoryStore.get(context) }
    val dsp = remember { AudioPipeline.controller(context) } // restores saved EQ/channel settings first
    val exo = remember { PlayerFactory.create(context) }
    val ui = remember(exo) { PlayerUiState(exo) }
    val panel = remember { PanelState() }
    val streamFormat by AudioPipeline.engine.format.collectAsState()
    val currentOnExit by rememberUpdatedState(onExit)

    var osdVisible by remember { mutableStateOf(true) }
    var osdTick by remember { mutableIntStateOf(0) }
    val meters = remember { mutableStateListOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) }

    fun poke() {
        osdVisible = true
        osdTick++
    }

    val panelController = remember(exo) {
        AudioPanelController(
            state = panel,
            dsp = dsp,
            channelCount = { AudioPipeline.engine.format.value.channelCount },
            trackItems = { buildTrackItems(ui.tracks) },
            onChoose = { choice -> selectTrack(exo, choice) },
        )
    }

    DisposableEffect(exo) {
        ui.attach()
        onDispose {
            if (ui.durationMs > 0 && ui.positionMs > 3_000) {
                history.record(uriKey, title, ui.positionMs, ui.durationMs)
            }
            ui.detach()
            exo.release()
        }
    }

    // Remember how far you got (Home > Recently Watched), every 5 seconds.
    LaunchedEffect(exo) {
        while (true) {
            delay(5000)
            if (ui.durationMs > 0 && ui.positionMs > 3_000) {
                history.record(uriKey, title, ui.positionMs, ui.durationMs)
            }
        }
    }

    LaunchedEffect(request) {
        val saved = history.find(uriKey)
        val startMs = if (settings.resumePlayback && saved != null && saved.positionMs > 5_000 && saved.progress < 0.95f) {
            saved.positionMs
        } else {
            0L
        }
        val subtitles = request.subtitles.mapIndexed { index, source ->
            MediaItem.SubtitleConfiguration.Builder(source.uri)
                .setMimeType(source.mimeType)
                .setLabel(source.label)
                .setSelectionFlags(if (index == 0 && settings.autoSubtitles) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        exo.setMediaItem(
            MediaItem.Builder().setUri(request.uri).setSubtitleConfigurations(subtitles).build(),
            startMs,
        )
        exo.prepare()
        exo.playWhenReady = true
    }

    LaunchedEffect(exo) {
        while (true) {
            ui.poll()
            delay(400)
        }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // OSD hides itself 4 s after the last key press while playing.
    LaunchedEffect(osdTick, osdVisible, ui.isPlaying, panel.open) {
        if (osdVisible && ui.isPlaying && !panel.open) {
            delay(settings.osdSeconds * 1000L)
            osdVisible = false
        }
    }

    // Live channel meters, only while the mixer tab is on screen.
    LaunchedEffect(panel.open, panel.tab) {
        val peaks = FloatArray(8)
        while (panel.open && panel.tab == PanelTab.CHANNELS) {
            val count = AudioPipeline.engine.readPeaks(peaks)
            for (i in 0 until 8) {
                val peak = if (i < count) peaks[i] else 0f
                val db = if (peak > 0.0001f) 20f * log10(peak) else -80f
                val level = ((db + 60f) / 60f).coerceIn(0f, 1f)
                meters[i] = maxOf(level, meters[i] - 0.07f)
            }
            delay(60)
        }
        for (i in 0 until 8) meters[i] = 0f
    }

    DisposableEffect(exo) {
        val handler: (RemoteKey, Int) -> Boolean = handler@{ key, repeat ->
            if (panelController.handleKey(key, repeat)) return@handler true
            when (key) {
                RemoteKey.SELECT, RemoteKey.PLAY_PAUSE -> {
                    if (exo.isPlaying) exo.pause() else exo.play()
                    poke()
                    true
                }
                RemoteKey.PLAY -> {
                    exo.play()
                    poke()
                    true
                }
                RemoteKey.PAUSE -> {
                    exo.pause()
                    poke()
                    true
                }
                RemoteKey.LEFT, RemoteKey.REWIND -> {
                    seekBy(exo, -seekStep(repeat, settings.seekStepSec * 1000L))
                    poke()
                    true
                }
                RemoteKey.RIGHT, RemoteKey.FAST_FORWARD -> {
                    seekBy(exo, seekStep(repeat, settings.seekStepSec * 1000L))
                    poke()
                    true
                }
                RemoteKey.UP -> {
                    if (osdVisible) osdVisible = false else poke()
                    true
                }
                RemoteKey.DOWN, RemoteKey.MENU -> {
                    osdVisible = false
                    panel.show()
                    true
                }
                RemoteKey.STOP -> {
                    currentOnExit()
                    true
                }
                else -> false
            }
        }
        KeyRouter.handler = handler
        onDispose { if (KeyRouter.handler === handler) KeyRouter.handler = null }
    }

    BackHandler {
        when {
            panel.open -> panel.back()
            osdVisible -> osdVisible = false
            else -> currentOnExit()
        }
    }

    val audioInfo = run {
        val format = selectedAudioFormat(ui.tracks)
        val stream = if (format != null) {
            listOf(
                codecName(format.sampleMimeType),
                if (format.channelCount > 0) channelLayoutName(format.channelCount) else "",
            ).filter { it.isNotEmpty() }.joinToString(" ")
        } else {
            ""
        }
        val dspPart = if (streamFormat.channelCount > 0) "DSP ${channelLayoutName(streamFormat.channelCount)}" else ""
        listOf(stream, dspPart).filter { it.isNotEmpty() }.joinToString("   ")
    }

    val formatInfo = if (streamFormat.channelCount > 0) {
        "${channelLayoutName(streamFormat.channelCount)}  |  ${streamFormat.sampleRate / 1000} kHz  |  " +
            if (streamFormat.floatPcm) "float" else "16-bit"
    } else {
        "waiting for audio"
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    isFocusableInTouchMode = false
                    player = exo
                }
            },
        )

        val errorText = ui.error
        if (errorText != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(48.dp)
                    .neuRaised(20.dp, 8.dp)
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NeoText("Cannot play this file", size = 24.sp, color = NeoColors.Danger)
                NeoText(errorText, size = 16.sp, color = NeoColors.TextSecondary, maxLines = 4)
                NeoText("Press BACK to return", size = 14.sp, color = NeoColors.TextDim)
            }
        } else if (ui.playbackState == Player.STATE_BUFFERING) {
            NeoText("Buffering...", size = 22.sp, color = NeoColors.TextSecondary, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = osdVisible && !panel.open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
            PlayerOsd(
                title = title,
                audioInfo = audioInfo,
                positionMs = ui.positionMs,
                durationMs = ui.durationMs,
                bufferedMs = ui.bufferedMs,
                isPlaying = ui.isPlaying,
            )
        }

        AnimatedVisibility(
            visible = panel.open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        ) {
            AudioPanel(
                controller = panelController,
                dsp = dsp,
                tracks = buildTrackItems(ui.tracks),
                channelCount = streamFormat.channelCount,
                formatInfo = formatInfo,
                meters = meters,
                sampleRate = streamFormat.sampleRate,
            )
        }
    }
}
