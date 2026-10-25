package com.resonix.tv.ui.shell

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.BuildConfig
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.player.PlayRequest
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoIconView
import com.resonix.tv.ui.components.NeoInfoCard
import com.resonix.tv.ui.components.NeoSectionHeader
import com.resonix.tv.ui.components.NeoSidebarItem
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.home.HomeSection
import com.resonix.tv.ui.settings.SettingsSection
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.update.UpdateSection
import com.resonix.tv.update.UpdateController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

enum class Section(val label: String, val icon: NeoIcon) {
    HOME("Home", NeoIcon.HOME),
    EQ("EQ", NeoIcon.EQ),
    SETTINGS("Settings", NeoIcon.SETTINGS),
    UPDATE("Update", NeoIcon.UPDATE),
}

/** The page currently shown on the right registers its key handler here. */
class SectionKeys {
    var handler: ((RemoteKey, Int) -> Boolean)? = null
}

private enum class NetKind { WIFI, ETHERNET, OFFLINE }

private fun networkKind(context: Context): NetKind = runCatching {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = manager.getNetworkCapabilities(manager.activeNetwork)
    when {
        caps == null -> NetKind.OFFLINE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.ETHERNET
        else -> NetKind.WIFI
    }
}.getOrDefault(NetKind.WIFI)

private fun clockText(context: Context): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}

@Composable
private fun NeoStatusBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var time by remember { mutableStateOf(clockText(context)) }
    var net by remember { mutableStateOf(networkKind(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            time = clockText(context)
            net = networkKind(context)
            delay(5000)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        NeoText(time, size = 20.sp, color = NeoColors.TextSecondary)
        NeoIconView(
            icon = when (net) {
                NetKind.WIFI -> NeoIcon.WIFI
                NetKind.ETHERNET -> NeoIcon.ETHERNET
                NetKind.OFFLINE -> NeoIcon.OFFLINE
            },
            tint = if (net == NetKind.OFFLINE) NeoColors.Warning else NeoColors.Cyan,
            iconSize = 26.dp,
        )
    }
}

@Composable
private fun EqSection(keys: SectionKeys) {
    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { _, _ -> false }
        keys.handler = handler
        onDispose { if (keys.handler === handler) keys.handler = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        NeoSectionHeader("Equalizer", "Channel mixer and 10-band parametric EQ", NeoIcon.EQ)
        NeoInfoCard(
            title = "Coming in the next update",
            value = "Full-screen equalizer",
            detail = "Until then: while a movie plays, press DOWN to open the channel mixer, the 10-band EQ and the presets.",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Left navigation bar + pages. Two zones: the bar and the page. RIGHT / OK enters the page,
 * LEFT (or BACK) on the left edge of a page returns to the bar.
 */
@Composable
fun MainShell(
    section: Section,
    onSectionChange: (Section) -> Unit,
    updates: UpdateController,
    onOpenBrowser: (File) -> Unit,
    onOpenHistory: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var inSidebar by remember { mutableStateOf(false) }
    val keys = remember { SectionKeys() }
    val currentSection by rememberUpdatedState(section)
    val currentOnChange by rememberUpdatedState(onSectionChange)

    BackHandler(enabled = !inSidebar) { inSidebar = true }

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, repeat ->
            if (inSidebar) {
                when (key) {
                    RemoteKey.UP -> {
                        currentOnChange(Section.entries[(currentSection.ordinal - 1).coerceAtLeast(0)])
                        true
                    }
                    RemoteKey.DOWN -> {
                        currentOnChange(Section.entries[(currentSection.ordinal + 1).coerceAtMost(Section.entries.lastIndex)])
                        true
                    }
                    RemoteKey.RIGHT, RemoteKey.SELECT -> {
                        inSidebar = false
                        true
                    }
                    else -> false
                }
            } else {
                val consumed = keys.handler?.invoke(key, repeat) ?: false
                if (consumed) {
                    true
                } else if (key == RemoteKey.LEFT) {
                    inSidebar = true
                    true
                } else {
                    false
                }
            }
        }
        KeyRouter.handler = handler
        onDispose { if (KeyRouter.handler === handler) KeyRouter.handler = null }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(246.dp).fillMaxHeight().padding(start = 26.dp, end = 18.dp, top = 30.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row {
                NeoText("Resonix", size = 32.sp, weight = FontWeight.Bold, color = Color.White)
                NeoText("TV", size = 32.sp, weight = FontWeight.Bold, color = NeoColors.Cyan)
            }
            Spacer(Modifier.height(26.dp))
            Section.entries.forEach { entry ->
                NeoSidebarItem(
                    label = entry.label,
                    icon = entry.icon,
                    selected = entry == section,
                    focused = inSidebar && entry == section,
                    badge = entry == Section.UPDATE && updates.updateAvailable,
                )
            }
            Spacer(Modifier.weight(1f))
            NeoText("Version ${BuildConfig.VERSION_NAME}", size = 12.sp, color = NeoColors.TextDim)
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.06f)))

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 36.dp, end = 44.dp, top = 26.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { NeoStatusBar() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    Section.HOME -> HomeSection(
                        active = !inSidebar,
                        keys = keys,
                        onOpenBrowser = onOpenBrowser,
                        onPlay = onPlay,
                        onOpenHistory = onOpenHistory,
                    )
                    Section.EQ -> EqSection(keys)
                    Section.SETTINGS -> SettingsSection(
                        active = !inSidebar,
                        keys = keys,
                        onOpenDiagnostics = onOpenDiagnostics,
                    )
                    Section.UPDATE -> UpdateSection(active = !inSidebar, keys = keys, updates = updates)
                }
            }
        }
    }
}
