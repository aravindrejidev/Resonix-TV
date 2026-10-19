package com.resonix.tv.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.resonix.tv.data.MediaLibrary
import com.resonix.tv.ui.diagnostics.DiagnosticsScreen
import com.resonix.tv.ui.home.HomeScreen
import com.resonix.tv.ui.player.PlayerScreen
import com.resonix.tv.ui.theme.NeoColors

sealed interface Screen {
    data object Home : Screen
    data class Player(val uri: Uri, val title: String) : Screen
    data object Diagnostics : Screen
}

/** Root of the app: Home -> Player, plus the diagnostics page. */
@Composable
fun ResonixApp(
    incomingUri: Uri?,
    onIncomingHandled: () -> Unit,
    nativeInfo: String,
    ffmpegInfo: String,
    activityKey: String,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    // "Open with ResonixTV" from a file manager or browser.
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            screen = Screen.Player(incomingUri, MediaLibrary.displayName(context, incomingUri))
            onIncomingHandled()
        }
    }

    Box(Modifier.fillMaxSize().background(NeoColors.Background)) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                onPlay = { uri, title -> screen = Screen.Player(uri, title) },
                onOpenDiagnostics = { screen = Screen.Diagnostics },
            )
            is Screen.Player -> key(current.uri) {
                PlayerScreen(current.uri, current.title, onExit = { screen = Screen.Home })
            }
            Screen.Diagnostics -> DiagnosticsScreen(
                nativeInfo = nativeInfo,
                ffmpegInfo = ffmpegInfo,
                activityKey = activityKey,
                onBack = { screen = Screen.Home },
            )
        }
    }
}
