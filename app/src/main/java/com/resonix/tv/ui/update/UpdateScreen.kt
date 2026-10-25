package com.resonix.tv.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.BuildConfig
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoInfoCard
import com.resonix.tv.ui.components.NeoProgress
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoSectionHeader
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.formatSize
import com.resonix.tv.ui.shell.SectionKeys
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.update.UpdateController
import com.resonix.tv.update.UpdateStatus
import com.resonix.tv.update.Updater

private data class UpdateText(
    val headline: String,
    val detail: String,
    val action: String,
    val latest: String,
    val isError: Boolean = false,
)

private fun describe(status: UpdateStatus, progress: Float): UpdateText = when (status) {
    UpdateStatus.Idle -> UpdateText("Updates", "Press OK to look for a new version.", "Check for updates", "-")
    UpdateStatus.Checking -> UpdateText("Checking...", "Asking GitHub for the newest release.", "Checking...", "...")
    UpdateStatus.UpToDate -> UpdateText("You are up to date", "Installed version ${Updater.currentVersion}.", "Check again", "Version ${Updater.currentVersion}")
    UpdateStatus.NotConfigured -> UpdateText(
        "Updates are not set up",
        "This APK was not built by GitHub Actions, so it does not know where to look for updates.",
        "Check for updates",
        "-",
    )
    is UpdateStatus.Available -> UpdateText(
        "Version ${status.info.version} is available",
        if (status.info.sizeBytes > 0) "Download size ${formatSize(status.info.sizeBytes)}." else "A new version is ready to download.",
        "Download version ${status.info.version}",
        "Version ${status.info.version}",
    )
    is UpdateStatus.Downloading -> UpdateText(
        "Downloading ${status.info.version}",
        "${(progress * 100).toInt()} %",
        "Downloading...",
        "Version ${status.info.version}",
    )
    is UpdateStatus.Ready -> UpdateText(
        "Version ${status.info.version} is ready",
        "The Android installer opens next. Confirm there; the app closes while it updates.",
        "Install now",
        "Version ${status.info.version}",
    )
    is UpdateStatus.NeedsPermission -> UpdateText(
        "Allow installing apps",
        "Android needs your permission once. Switch it on for ResonixTV, press BACK, then press OK again.",
        "Install now",
        "Version ${status.info.version}",
    )
    is UpdateStatus.Failed -> UpdateText("Something went wrong", status.message, "Try again", "-", isError = true)
}

/** Update page: OK does the next step (check -> download -> install). */
@Composable
fun UpdateSection(active: Boolean, keys: SectionKeys, updates: UpdateController) {
    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, _ ->
            if (key == RemoteKey.SELECT) {
                updates.act()
                true
            } else {
                false
            }
        }
        keys.handler = handler
        onDispose { if (keys.handler === handler) keys.handler = null }
    }

    val status = updates.status
    val text = describe(status, updates.progress)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NeoSectionHeader("Update", "Keep ResonixTV up to date", NeoIcon.UPDATE)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            NeoInfoCard(
                title = "Installed",
                value = "Version ${Updater.currentVersion}",
                detail = "Build ${BuildConfig.VERSION_CODE}",
                modifier = Modifier.weight(1f),
            )
            NeoInfoCard(
                title = "Latest",
                value = text.latest,
                detail = if (Updater.repo.isBlank()) "Source not set" else "github.com/${Updater.repo}",
                modifier = Modifier.weight(1f),
                accent = if (updates.updateAvailable) NeoColors.Warning else NeoColors.Cyan,
            )
        }

        NeoText(
            text = text.headline,
            size = 24.sp,
            color = if (text.isError) NeoColors.Danger else NeoColors.TextPrimary,
            weight = FontWeight.SemiBold,
        )
        NeoText(text.detail, size = 16.sp, color = NeoColors.TextSecondary, maxLines = 3)
        if (status is UpdateStatus.Downloading) NeoProgress(progress = updates.progress, buffered = 0f)
        if (status is UpdateStatus.Available) {
            val notes = status.info.notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(6).joinToString("\n")
            if (notes.isNotEmpty()) {
                NeoText("WHAT IS NEW", size = 12.sp, color = NeoColors.TextDim, weight = FontWeight.SemiBold)
                NeoText(notes, size = 15.sp, color = NeoColors.TextSecondary, maxLines = 6)
            }
        }
        NeoRow(title = text.action, subtitle = null, focused = active, icon = NeoIcon.UPDATE)
    }
}
