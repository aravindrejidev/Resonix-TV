package com.resonix.tv.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.resonix.tv.diag.AppLog

/**
 * What the player reports while a movie runs: which decoders were really started (hardware or
 * software, FFmpeg or the TV's own) and how many video frames were dropped. Shown in the Info tab
 * of the audio panel and written to the log, so a stutter can be traced to its cause.
 */
@OptIn(UnstableApi::class)
@Stable
class PlaybackStats : AnalyticsListener {
    var videoDecoder by mutableStateOf("")
        private set
    var audioDecoder by mutableStateOf("")
        private set
    var droppedFrames by mutableIntStateOf(0)
        private set

    /** What the frame-rate matching did for this movie (set by the player screen). */
    var frameRateNote by mutableStateOf("Off")

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoder = decoderName
        AppLog.log("VIDEO_DECODER", "$decoderName (started in $initializationDurationMs ms)")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoder = decoderName
        AppLog.log("AUDIO_DECODER", "$decoderName (started in $initializationDurationMs ms)")
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        this.droppedFrames = this.droppedFrames + droppedFrames
        // Only worth a log line when it is more than an occasional single frame.
        if (droppedFrames >= 10) AppLog.log("DROPPED_FRAMES", "$droppedFrames frames in $elapsedMs ms")
    }
}
