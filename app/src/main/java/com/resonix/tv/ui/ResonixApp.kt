package com.resonix.tv.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
import com.resonix.tv.ui.history.HistoryScreen
import com.resonix.tv.ui.player.PlayerScreen
import com.resonix.tv.ui.shell.MainShell
import com.resonix.tv.ui.shell.Section
import com.resonix.tv.ui.theme.neoBackground
import com.resonix.tv.update.UpdateController
import java.io.File

/** Full-screen pages that open on top of the main shell. BACK returns to the page below. */
sealed interface Overlay {
    data class Browser(val startDir: File?) : Overlay
    data object History : Overlay
    data class Player(val request: PlayRequest) : Overlay
    data object Diagnostics : Overlay
}

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
    val updates = remember { UpdateController(context.applicationContext, scope) }
    var section by remember { mutableStateOf(Section.HOME) }
    val stack = remember { mutableStateListOf<Overlay>() }

    fun push(overlay: Overlay) {
        stack.add(overlay)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    // Quietly look for a newer version once per start.
    LaunchedEffect(Unit) { updates.check() }

    // "Open with ResonixTV" from a file manager or browser.
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            stack.clear()
            stack.add(Overlay.Player(PlayRequest(incomingUri, MediaLibrary.displayName(context, incomingUri))))
            onIncomingHandled()
        }
    }

    Box(Modifier.fillMaxSize().neoBackground()) {
        when (val top = stack.lastOrNull()) {
            null -> MainShell(
                section = section,
                onSectionChange = { section = it },
                updates = updates,
                onOpenBrowser = { dir -> push(Overlay.Browser(dir)) },
                onOpenHistory = { push(Overlay.History) },
                onPlay = { request -> push(Overlay.Player(request)) },
                onOpenDiagnostics = { push(Overlay.Diagnostics) },
            )
            is Overlay.Browser -> key(top) {
                BrowserScreen(
                    startDir = top.startDir,
                    onPlay = { request -> push(Overlay.Player(request)) },
                    onExit = { pop() },
                )
            }
            Overlay.History -> HistoryScreen(
                onPlay = { request -> push(Overlay.Player(request)) },
                onExit = { pop() },
            )
            is Overlay.Player -> key(top.request.uri) {
                PlayerScreen(top.request, onExit = { pop() })
            }
            Overlay.Diagnostics -> DiagnosticsScreen(
                nativeInfo = nativeInfo,
                ffmpegInfo = ffmpegInfo,
                activityKey = activityKey,
                onBack = { pop() },
            )
        }
    }
}
