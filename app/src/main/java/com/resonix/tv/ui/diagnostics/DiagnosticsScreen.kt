package com.resonix.tv.ui.diagnostics

import android.util.Log
import androidx.activity.compose.BackHandler
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
import com.resonix.tv.input.RemoteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun infoStyle(color: Color) = TextStyle(color = color, fontSize = 20.sp)

/** Engine check screen: native library, DSP self-test (runs on this TV), FFmpeg codecs, remote keys. */
@Composable
fun DiagnosticsScreen(
    nativeInfo: String,
    ffmpegInfo: String,
    activityKey: String,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var composeKey by remember { mutableStateOf("-") }

    val dspSummary by produceState(initialValue = "DSP self-test: running...") {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val report = DspNative.selfTest()
                Log.i("ResonixDSP", report)
                report.lineSequence().first()
            }.getOrElse { "DSP self-test unavailable (${it.javaClass.simpleName})" }
        }
    }

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
                text = "Diagnostics",
                style = TextStyle(color = Color(0xFF00E5FF), fontSize = 48.sp, fontWeight = FontWeight.Bold),
            )
            val grey = Color(0xFF9AA0AC)
            BasicText(text = nativeInfo, style = infoStyle(grey))
            BasicText(
                text = dspSummary,
                style = infoStyle(if (dspSummary.contains("FAIL")) Color(0xFFFF5252) else grey),
            )
            BasicText(text = ffmpegInfo, style = infoStyle(grey))
            BasicText(text = activityKey, style = TextStyle(color = Color(0xFFE6E8EC), fontSize = 24.sp))
            BasicText(
                text = "Compose: focus " + (if (hasFocus) "yes" else "NO") + " | key " + composeKey,
                style = infoStyle(if (hasFocus) grey else Color(0xFFFFB74D)),
            )
            BasicText(text = "Press BACK to return", style = infoStyle(Color(0xFF5C6370)))
        }
    }
}
