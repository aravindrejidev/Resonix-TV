package com.resonix.tv

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.audio.DspNative
import com.resonix.tv.audio.NativeBridge
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.player.FfmpegInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Diagnostics screen (modules 1 + 3): proves that the TV launcher entry, the native library
 * (Oboe), the DSP engine, the FFmpeg decoders and the remote-control keys all work.
 * The real UI replaces BootScreen in module 4.
 */
class MainActivity : ComponentActivity() {

    private var activityKey by mutableStateOf("Press any remote key")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nativeInfo = runCatching { NativeBridge.nativeVersion() }
            .getOrElse { "Native engine unavailable (${it.javaClass.simpleName})" }
        val ffmpegInfo = FfmpegInfo.describe()

        setContent { BootScreen(nativeInfo, ffmpegInfo, activityKey) }
    }

    /** Sees every key press, no matter which Compose element (if any) currently has focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val mapped = RemoteKey.fromKeyCode(event.keyCode)
            val name = KeyEvent.keyCodeToString(event.keyCode)
            activityKey = "Activity key: $name (${event.keyCode}) " +
                if (mapped != null) "-> ${mapped.name}" else "(not mapped)"
            Log.i("ResonixKeys", activityKey)
        }
        return super.dispatchKeyEvent(event)
    }
}

private fun infoStyle(color: Color) = TextStyle(color = color, fontSize = 20.sp)

@Composable
private fun BootScreen(nativeInfo: String, ffmpegInfo: String, activityKey: String) {
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var composeKey by remember { mutableStateOf("-") }

    // The DSP self-test runs on this device, off the UI thread.
    val dspSummary by produceState(initialValue = "DSP self-test: running...") {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val report = DspNative.selfTest()
                Log.i("ResonixDSP", report)
                report.lineSequence().first()
            }.getOrElse { "DSP self-test unavailable (${it.javaClass.simpleName})" }
        }
    }

    // On a TV focus is not always available right at start-up: retry until Compose reports it.
    LaunchedEffect(Unit) {
        var attempts = 0
        while (!hasFocus && attempts < 10) {
            runCatching { focusRequester.requestFocus() }
            delay(250)
            attempts++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121418))
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.isFocused }
            .onPreviewKeyEvent { event ->
                val key = RemoteKey.from(event) ?: return@onPreviewKeyEvent false
                composeKey = key.name
                // Let BACK fall through so the app can still be closed.
                key != RemoteKey.BACK
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BasicText(
                text = "ResonixTV",
                style = TextStyle(
                    color = Color(0xFF00E5FF),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            val grey = Color(0xFF9AA0AC)
            BasicText(text = nativeInfo, style = infoStyle(grey))
            BasicText(
                text = dspSummary,
                style = infoStyle(if (dspSummary.contains("FAIL")) Color(0xFFFF5252) else grey),
            )
            BasicText(text = ffmpegInfo, style = infoStyle(grey))
            BasicText(
                text = activityKey,
                style = TextStyle(color = Color(0xFFE6E8EC), fontSize = 24.sp),
            )
            BasicText(
                text = "Compose: focus " + (if (hasFocus) "yes" else "NO") + " | key " + composeKey,
                style = infoStyle(if (hasFocus) grey else Color(0xFFFFB74D)),
            )
        }
    }
}
