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
import com.resonix.tv.audio.AudioPipeline
import com.resonix.tv.audio.NativeBridge
import com.resonix.tv.diag.AppLog
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.player.FfmpegInfo
import com.resonix.tv.player.PlayerLifecycle
import com.resonix.tv.ui.ResonixApp

class MainActivity : ComponentActivity() {

    private var activityKey by mutableStateOf("Press any remote key")
    private var incomingUri by mutableStateOf<Uri?>(null)
    private var nativeInfo by mutableStateOf("Native engine: checking...")
    private var ffmpegInfo by mutableStateOf("FFmpeg decoders: checking...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        AppLog.init(applicationContext)
        warmUp()

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

    /**
     * Loads the native libraries (sound engine, FFmpeg decoders) and restores the saved EQ settings
     * in the background while the first screen is drawn. The first movie then starts without
     * waiting for them, and the app opens faster because none of this blocks the main thread.
     */
    private fun warmUp() {
        val appContext = applicationContext
        Thread({
            nativeInfo = runCatching { NativeBridge.nativeVersion() }
                .getOrElse { "Native engine unavailable (${it.javaClass.simpleName})" }
            ffmpegInfo = FfmpegInfo.describe()
            runCatching { AudioPipeline.controller(appContext) }
        }, "resonix-warmup").start()
    }

    /** Home button, TV going to sleep, screen saver: pause the movie and remember where it stopped. */
    override fun onStop() {
        super.onStop()
        PlayerLifecycle.onAppStopped?.invoke()
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
