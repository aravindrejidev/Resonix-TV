package com.resonix.tv.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.BuildConfig
import com.resonix.tv.data.MediaEntry
import com.resonix.tv.data.MediaLibrary
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.player.PlayRequest
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.formatSize
import com.resonix.tv.ui.formatTime
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.update.UpdateController
import com.resonix.tv.update.UpdateStatus
import com.resonix.tv.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface HomeRow {
    data object Browse : HomeRow
    data object Grant : HomeRow
    data object Updates : HomeRow
    data object Diagnostics : HomeRow
    data class Video(val entry: MediaEntry) : HomeRow
}

/** Start screen: file browser, updates, and the videos Android has indexed (internal + USB). */
@Composable
fun HomeScreen(
    updates: UpdateController,
    onPlay: (PlayRequest) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnBrowser by rememberUpdatedState(onOpenBrowser)
    val currentOnUpdates by rememberUpdatedState(onOpenUpdates)
    val currentOnDiagnostics by rememberUpdatedState(onOpenDiagnostics)

    var hasPermission by remember { mutableStateOf(MediaLibrary.hasPermission(context)) }
    var loading by remember { mutableStateOf(false) }
    var videos by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var cursor by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermission = MediaLibrary.hasPermission(context)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            loading = true
            videos = withContext(Dispatchers.IO) { MediaLibrary.loadVideos(context) }
            loading = false
        } else {
            runCatching { permissionLauncher.launch(MediaLibrary.permissions()) }
        }
    }

    val rows: List<HomeRow> = remember(videos, hasPermission) {
        buildList<HomeRow> {
            add(HomeRow.Browse)
            if (!hasPermission) add(HomeRow.Grant)
            add(HomeRow.Updates)
            add(HomeRow.Diagnostics)
            videos.forEach { add(HomeRow.Video(it)) }
        }
    }
    val latestRows by rememberUpdatedState(rows)

    LaunchedEffect(rows.size) { cursor = cursor.coerceIn(0, rows.lastIndex) }

    val activate: (HomeRow) -> Unit = { row ->
        when (row) {
            HomeRow.Browse -> currentOnBrowser()
            HomeRow.Grant -> runCatching { permissionLauncher.launch(MediaLibrary.permissions()) }
            HomeRow.Updates -> currentOnUpdates()
            HomeRow.Diagnostics -> currentOnDiagnostics()
            is HomeRow.Video -> currentOnPlay(PlayRequest(row.entry.uri, row.entry.title))
        }
    }
    val latestActivate by rememberUpdatedState(activate)

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, repeat ->
            val step = if (repeat > 10) 5 else 1
            when (key) {
                RemoteKey.UP -> {
                    cursor = (cursor - step).coerceAtLeast(0)
                    true
                }
                RemoteKey.DOWN -> {
                    cursor = (cursor + step).coerceAtMost(latestRows.lastIndex)
                    true
                }
                RemoteKey.SELECT -> {
                    latestRows.getOrNull(cursor)?.let { latestActivate(it) }
                    true
                }
                else -> false
            }
        }
        KeyRouter.handler = handler
        onDispose { if (KeyRouter.handler === handler) KeyRouter.handler = null }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(cursor) { listState.animateScrollToItem((cursor - 2).coerceAtLeast(0)) }

    val status = when {
        !hasPermission -> "Allow media access to list the videos on this TV and on USB drives."
        loading -> "Scanning for videos..."
        videos.isEmpty() -> "No indexed videos yet. Use Browse files to open a movie from any folder or USB drive."
        else -> "${videos.size} videos found"
    }

    val updateSubtitle = when (val s = updates.status) {
        is UpdateStatus.Available -> "Version ${s.info.version} is available"
        is UpdateStatus.Downloading -> "Downloading ${s.info.version}..."
        is UpdateStatus.Ready, is UpdateStatus.NeedsPermission -> "Ready to install"
        UpdateStatus.Checking -> "Checking..."
        UpdateStatus.UpToDate -> "Up to date (version ${Updater.currentVersion})"
        is UpdateStatus.Failed -> "Could not check for updates"
        else -> "Installed version ${Updater.currentVersion}"
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
            NeoText("ResonixTV", size = 44.sp, color = NeoColors.Cyan, weight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            NeoText(
                "Multi-channel mixer and parametric equalizer for your movies",
                size = 16.sp,
                color = NeoColors.TextSecondary,
                maxLines = 3,
            )
            Spacer(Modifier.height(18.dp))
            NeoText(status, size = 14.sp, color = NeoColors.TextDim, maxLines = 4)
            Spacer(Modifier.weight(1f))
            NeoText(
                "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                size = 13.sp,
                color = NeoColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NeoChip("UP / DOWN  choose")
                NeoChip("OK  open")
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
        ) {
            itemsIndexed(rows) { index, row ->
                val focused = index == cursor
                when (row) {
                    HomeRow.Browse -> NeoRow("Browse files", "Storage devices, USB drives and folders", focused)
                    HomeRow.Grant -> NeoRow("Allow access to media", "Needed to list the videos on this TV and on USB drives", focused)
                    HomeRow.Updates -> NeoRow(
                        title = "Updates",
                        subtitle = updateSubtitle,
                        focused = focused,
                        marker = if (updates.updateAvailable) "NEW" else null,
                    )
                    HomeRow.Diagnostics -> NeoRow("Diagnostics", "Audio engine, FFmpeg decoders and remote keys", focused)
                    is HomeRow.Video -> NeoRow(
                        title = row.entry.title,
                        subtitle = "${formatTime(row.entry.durationMs)}   |   ${formatSize(row.entry.sizeBytes)}",
                        focused = focused,
                    )
                }
            }
        }
    }
}
