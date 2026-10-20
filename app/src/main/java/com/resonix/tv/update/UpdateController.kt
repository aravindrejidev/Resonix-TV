package com.resonix.tv.update

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Compose-observable state machine: check -> download -> install. One key press = one step. */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    private var busy = false

    val updateAvailable: Boolean
        get() = when (status) {
            is UpdateStatus.Available, is UpdateStatus.Downloading,
            is UpdateStatus.Ready, is UpdateStatus.NeedsPermission -> true
            else -> false
        }

    fun check() {
        if (busy) return
        busy = true
        status = UpdateStatus.Checking
        scope.launch {
            status = Updater.fetchLatest()
            busy = false
        }
    }

    /** What the OK key does in the current state. */
    fun act() {
        when (val s = status) {
            is UpdateStatus.Available -> download(s.info)
            is UpdateStatus.Ready -> install(s.info, s.file)
            is UpdateStatus.NeedsPermission -> install(s.info, s.file)
            is UpdateStatus.Downloading, UpdateStatus.Checking -> Unit
            else -> check()
        }
    }

    private fun download(info: UpdateInfo) {
        if (busy) return
        busy = true
        progress = 0f
        status = UpdateStatus.Downloading(info)
        scope.launch {
            val result = Updater.download(context, info) { progress = it }
            status = result.fold(
                onSuccess = { UpdateStatus.Ready(info, it) },
                onFailure = { UpdateStatus.Failed(it.message ?: "Download failed") },
            )
            busy = false
        }
    }

    private fun install(info: UpdateInfo, file: java.io.File) {
        if (!Updater.canInstall(context)) {
            status = UpdateStatus.NeedsPermission(info, file)
            val opened = runCatching { context.startActivity(Updater.installPermissionIntent(context)) }.isSuccess
            if (!opened) {
                Toast.makeText(
                    context,
                    "Open Settings > Security & restrictions > Unknown sources and allow ResonixTV, then press OK again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        Updater.install(context, file).onFailure {
            status = UpdateStatus.Failed("Could not start the installer: ${it.message}")
        }
    }
}
