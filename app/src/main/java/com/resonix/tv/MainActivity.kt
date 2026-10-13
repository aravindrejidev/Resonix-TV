package com.resonix.tv

import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.audio.NativeBridge
import com.resonix.tv.input.RemoteKey

/**
 * Module 1 placeholder: proves that the TV launcher entry, the native library (Oboe) and the
 * remote-control key mapping all work. The real UI replaces BootScreen in module 4.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nativeInfo = runCatching { NativeBridge.nativeVersion() }
            .getOrElse { "Native engine unavailable (${it.javaClass.simpleName})" }

        setContent { BootScreen(nativeInfo) }
    }
}

@Composable
private fun BootScreen(nativeInfo: String) {
    val focusRequester = remember { FocusRequester() }
    var lastKey by remember { mutableStateOf("Press any remote key") }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121418))
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val key = RemoteKey.from(event) ?: return@onPreviewKeyEvent false
                lastKey = "Last key: ${key.name}"
                // Let BACK fall through so the app can still be closed.
                key != RemoteKey.BACK
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(
                text = "ResonixTV",
                style = TextStyle(
                    color = Color(0xFF00E5FF),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            BasicText(
                text = nativeInfo,
                style = TextStyle(color = Color(0xFF9AA0AC), fontSize = 20.sp),
            )
            BasicText(
                text = lastKey,
                style = TextStyle(color = Color(0xFFE6E8EC), fontSize = 24.sp),
            )
        }
    }
}
