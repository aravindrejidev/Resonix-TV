package com.resonix.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.resonix.tv.audio.NativeBridge
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.player.FfmpegInfo
import com.resonix.tv.ui.ResonixApp

class MainActivity : ComponentActivity() {

    private var activityKey by mutableStateOf("Press any remote key")
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        val nativeInfo = runCatching { NativeBridge.nativeVersion() }
            .getOrElse { "Native engine unavailable (${it.javaClass.simpleName})" }
        val ffmpegInfo = FfmpegInfo.describe()

        setContent {
            ResonixApp(
                incomingUri = incomingUri,
                onIncomingHandled = { incomingUri = null },
                nativeInfo = nativeInfo,
                ffmpegInfo = ffmpegInfo,
                activityKey = activityKey,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            if (data != null) incomingUri = data
        }
    }

    /**
     * Every key press passes here first. The screens are driven by [KeyRouter], so the UI works
     * whatever Compose currently has focused; unhandled keys continue to Compose as usual.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val mapped = RemoteKey.fromKeyCode(event.keyCode)
            if (event.repeatCount == 0) {
                activityKey = "Activity key: ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) " +
                    if (mapped != null) "-> ${mapped.name}" else "(not mapped)"
                Log.i("ResonixKeys", activityKey)
            }
            // BACK is left to the system so BackHandler works on every Android version.
            if (mapped != null && mapped != RemoteKey.BACK && KeyRouter.dispatch(mapped, event.repeatCount)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
