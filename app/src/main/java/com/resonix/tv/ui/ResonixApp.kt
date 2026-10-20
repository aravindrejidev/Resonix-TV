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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.resonix.tv.data.MediaLibrary
import com.resonix.tv.player.PlayRequest
import com.resonix.tv.ui.browser.BrowserScreen
import com.resonix.tv.ui.diagnostics.DiagnosticsScreen
import com.resonix.tv.ui.home.HomeScreen
import com.resonix.tv.ui.player.PlayerScreen
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.update.UpdateScreen
import com.resonix.tv.update.UpdateController

sealed interface Screen {
    data object Home : Screen
    data object Browser : Screen
    data class Player(val request: PlayRequest) : Screen
    data object Updates : Screen
    data object Diagnostics : Screen
}

/** Root of the app: Home -> Browser / Player / Updates / Diagnostics. */
@Composable
fun ResonixApp(
    incomingUri: Uri?,
    onIncomingHandled: () -> Unit,
    nativeInfo: String,
    ffmpegInfo: String,
    activityKey: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val updates = remember { UpdateController(context.applicationContext, scope) }

    // Quietly look for a newer version once per start.
    LaunchedEffect(Unit) { updates.check() }

    // "Open with ResonixTV" from a file manager or browser.
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            screen = Screen.Player(PlayRequest(incomingUri, MediaLibrary.displayName(context, incomingUri)))
            onIncomingHandled()
        }
    }

    Box(Modifier.fillMaxSize().background(NeoColors.Background)) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                updates = updates,
                onPlay = { request -> screen = Screen.Player(request) },
                onOpenBrowser = { screen = Screen.Browser },
                onOpenUpdates = { screen = Screen.Updates },
                onOpenDiagnostics = { screen = Screen.Diagnostics },
            )
            Screen.Browser -> BrowserScreen(
                onPlay = { request -> screen = Screen.Player(request) },
                onExit = { screen = Screen.Home },
            )
            is Screen.Player -> key(current.request.uri) {
                PlayerScreen(current.request, onExit = { screen = Screen.Home })
            }
            Screen.Updates -> UpdateScreen(updates = updates, onBack = { screen = Screen.Home })
            Screen.Diagnostics -> DiagnosticsScreen(
                nativeInfo = nativeInfo,
                ffmpegInfo = ffmpegInfo,
                activityKey = activityKey,
                onBack = { screen = Screen.Home },
            )
        }
    }
}
