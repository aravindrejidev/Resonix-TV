package com.resonix.tv.ui.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.BuildConfig
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoProgress
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.formatSize
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.update.UpdateController
import com.resonix.tv.update.UpdateStatus
import com.resonix.tv.update.Updater

private data class UpdateText(val headline: String, val detail: String, val action: String, val isError: Boolean = false)

private fun describe(status: UpdateStatus, progress: Float): UpdateText = when (status) {
    UpdateStatus.Idle -> UpdateText("Updates", "Press OK to look for a new version.", "Check for updates")
    UpdateStatus.Checking -> UpdateText("Checking...", "Asking GitHub for the newest release.", "Checking...")
    UpdateStatus.UpToDate -> UpdateText("You are up to date", "Installed version ${Updater.currentVersion}.", "Check again")
    UpdateStatus.NotConfigured -> UpdateText(
        "Updates are not set up",
        "This APK was not built by GitHub Actions, so it does not know where to look for updates.",
        "Check for updates",
    )
    is UpdateStatus.Available -> UpdateText(
        "Version ${status.info.version} is available",
        if (status.info.sizeBytes > 0) "Download size ${formatSize(status.info.sizeBytes)}." else "A new version is ready to download.",
        "Download version ${status.info.version}",
    )
    is UpdateStatus.Downloading -> UpdateText(
        "Downloading ${status.info.version}",
        "${(progress * 100).toInt()} %",
        "Downloading...",
    )
    is UpdateStatus.Ready -> UpdateText(
        "Version ${status.info.version} is ready",
        "The Android installer opens next. Confirm there; the app closes while it updates.",
        "Install now",
    )
    is UpdateStatus.NeedsPermission -> UpdateText(
        "Allow installing apps",
        "Android needs your permission once. Switch it on for ResonixTV, press BACK, then press OK again.",
        "Install now",
    )
    is UpdateStatus.Failed -> UpdateText("Something went wrong", status.message, "Try again", isError = true)
}

/** Updates page: OK does the next step (check -> download -> install). */
@Composable
fun UpdateScreen(updates: UpdateController, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, _ ->
            if (key == RemoteKey.SELECT) {
                updates.act()
                true
            } else {
                false
            }
        }
        KeyRouter.handler = handler
        onDispose { if (KeyRouter.handler === handler) KeyRouter.handler = null }
    }

    val status = updates.status
    val text = describe(status, updates.progress)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 120.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NeoText("Updates", size = 40.sp, color = NeoColors.Cyan, weight = FontWeight.Bold)
        NeoText(
            "Installed: version ${Updater.currentVersion} (build ${BuildConfig.VERSION_CODE})",
            size = 18.sp,
            color = NeoColors.TextSecondary,
        )
        NeoText(
            "Source: " + if (Updater.repo.isBlank()) "not set" else "github.com/${Updater.repo}",
            size = 14.sp,
            color = NeoColors.TextDim,
        )
        Spacer(Modifier.height(18.dp))

        NeoText(text.headline, size = 28.sp, color = if (text.isError) NeoColors.Danger else NeoColors.TextPrimary, weight = FontWeight.SemiBold)
        NeoText(text.detail, size = 18.sp, color = NeoColors.TextSecondary, maxLines = 3)
        if (status is UpdateStatus.Downloading) {
            NeoProgress(progress = updates.progress, buffered = 0f)
        }
        if (status is UpdateStatus.Available) {
            val notes = status.info.notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(8).joinToString("\n")
            if (notes.isNotEmpty()) {
                NeoText("What is new", size = 14.sp, color = NeoColors.TextDim, weight = FontWeight.SemiBold)
                NeoText(notes, size = 15.sp, color = NeoColors.TextSecondary, maxLines = 8)
            }
        }

        Spacer(Modifier.weight(1f))
        NeoRow(title = text.action, subtitle = null, focused = true)
        NeoChip("OK  do this step        BACK  return")
    }
}
