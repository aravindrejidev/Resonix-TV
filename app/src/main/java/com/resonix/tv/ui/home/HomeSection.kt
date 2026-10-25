package com.resonix.tv.ui.home

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.data.FileBrowser
import com.resonix.tv.data.HistoryEntry
import com.resonix.tv.data.HistoryStore
import com.resonix.tv.data.StorageInfo
import com.resonix.tv.data.StorageRoots
import com.resonix.tv.data.listWithUsage
import com.resonix.tv.data.rememberThumbnail
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.player.PlayRequest
import com.resonix.tv.ui.components.NeoHistoryCard
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoIconTile
import com.resonix.tv.ui.components.NeoSectionHeader
import com.resonix.tv.ui.components.NeoStorageCard
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.components.NeoViewAllTile
import com.resonix.tv.ui.formatSize
import com.resonix.tv.ui.formatTime
import com.resonix.tv.ui.shell.SectionKeys
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.theme.neuInset
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun NoUsbCard(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.neuInset(22.dp).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconTile(NeoIcon.USB, NeoColors.TextDim, tileSize = 60.dp)
        Spacer(Modifier.width(18.dp))
        Column {
            NeoText("No USB drive", size = 20.sp, color = NeoColors.TextSecondary, weight = FontWeight.SemiBold)
            NeoText("Plug one into the TV and it shows up here", size = 14.sp, color = NeoColors.TextDim, maxLines = 2)
        }
    }
}

/**
 * Home page: storage devices (open the file browser) and recently watched movies (resume).
 * D-pad: LEFT/RIGHT along a row, DOWN/UP between the rows, OK opens.
 */
@Composable
fun HomeSection(
    active: Boolean,
    keys: SectionKeys,
    onOpenBrowser: (File) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { HistoryStore.get(context) }
    val currentOnBrowser by rememberUpdatedState(onOpenBrowser)
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnHistory by rememberUpdatedState(onOpenHistory)

    var storage by remember { mutableStateOf<List<StorageInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            val fresh = withContext(Dispatchers.IO) { StorageRoots.listWithUsage(context) }
            if (fresh != storage) storage = fresh
            delay(3000)
        }
    }

    val recent: List<HistoryEntry> = history.entries.take(8)
    val latestStorage by rememberUpdatedState(storage)
    val latestRecent by rememberUpdatedState(recent)

    var row by remember { mutableIntStateOf(0) }
    var storageCol by remember { mutableIntStateOf(0) }
    var recentCol by remember { mutableIntStateOf(0) }
    val storageState = rememberLazyListState()
    val recentState = rememberLazyListState()
    val scroll = rememberScrollState()

    LaunchedEffect(storageCol) { storageState.animateScrollToItem(storageCol.coerceAtLeast(0)) }
    LaunchedEffect(recentCol) { recentState.animateScrollToItem(recentCol.coerceAtLeast(0)) }
    LaunchedEffect(row) { scroll.animateScrollTo(if (row == 0) 0 else scroll.maxValue) }
    LaunchedEffect(storage.size) { storageCol = storageCol.coerceIn(0, (storage.size - 1).coerceAtLeast(0)) }
    LaunchedEffect(recent.size) {
        recentCol = recentCol.coerceIn(0, recent.size)
        if (recent.isEmpty()) row = 0
    }

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, _ ->
            val storageCount = latestStorage.size
            val recentCount = if (latestRecent.isEmpty()) 0 else latestRecent.size + 1 // + "View all"
            when (key) {
                RemoteKey.LEFT -> {
                    if (row == 0 && storageCol > 0) {
                        storageCol -= 1
                        true
                    } else if (row == 1 && recentCol > 0) {
                        recentCol -= 1
                        true
                    } else {
                        false
                    }
                }
                RemoteKey.RIGHT -> {
                    if (row == 0) {
                        storageCol = (storageCol + 1).coerceAtMost((storageCount - 1).coerceAtLeast(0))
                    } else {
                        recentCol = (recentCol + 1).coerceAtMost((recentCount - 1).coerceAtLeast(0))
                    }
                    true
                }
                RemoteKey.UP -> {
                    if (row == 1) {
                        row = 0
                        true
                    } else {
                        false
                    }
                }
                RemoteKey.DOWN -> {
                    if (row == 0 && recentCount > 0) row = 1
                    true
                }
                RemoteKey.SELECT -> {
                    if (row == 0) {
                        latestStorage.getOrNull(storageCol)?.let { currentOnBrowser(it.root.path) }
                    } else {
                        val entry = latestRecent.getOrNull(recentCol)
                        if (entry != null) {
                            scope.launch {
                                val uri = Uri.parse(entry.uri)
                                val subtitles = withContext(Dispatchers.IO) { FileBrowser.findSubtitlesFor(uri) }
                                currentOnPlay(PlayRequest(uri, entry.title, subtitles))
                            }
                        } else {
                            currentOnHistory()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        keys.handler = handler
        onDispose { if (keys.handler === handler) keys.handler = null }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            NeoSectionHeader("Storage Drives", "Browse your connected drives and folders", NeoIcon.DRIVE, Modifier.padding(start = 24.dp))
            LazyRow(
                state = storageState,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            ) {
                itemsIndexed(storage) { index, info ->
                    val used = (info.totalBytes - info.freeBytes).coerceAtLeast(0L)
                    val internal = index == 0
                    NeoStorageCard(
                        title = if (internal) "Internal Storage" else "USB $index",
                        subtitle = if (internal) "Local files" else info.root.label.ifBlank { "External storage" },
                        usageText = "${formatSize(used)} / ${formatSize(info.totalBytes)}",
                        fraction = if (info.totalBytes > 0) used.toFloat() / info.totalBytes else 0f,
                        icon = if (internal) NeoIcon.DRIVE else NeoIcon.USB,
                        accent = if (internal) NeoColors.Cyan else NeoColors.Purple,
                        accent2 = if (internal) NeoColors.Blue else NeoColors.Pink,
                        focused = active && row == 0 && storageCol == index,
                        modifier = Modifier.width(340.dp),
                    )
                }
                if (storage.size < 2) {
                    item { NoUsbCard(Modifier.width(340.dp)) }
                }
            }
        }

        Column {
            NeoSectionHeader("Recently Watched", null, NeoIcon.HISTORY, Modifier.padding(start = 24.dp))
            Spacer(Modifier.height(4.dp))
            if (recent.isEmpty()) {
                NeoText(
                    text = "Nothing watched yet. Open a drive above and play a movie.",
                    size = 16.sp,
                    color = NeoColors.TextDim,
                    modifier = Modifier.padding(start = 24.dp, top = 12.dp),
                )
            } else {
                LazyRow(
                    state = recentState,
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                ) {
                    itemsIndexed(recent) { index, entry ->
                        NeoHistoryCard(
                            title = entry.title,
                            durationText = formatTime(entry.durationMs),
                            progress = entry.progress,
                            thumbnail = rememberThumbnail(entry.uri),
                            focused = active && row == 1 && recentCol == index,
                        )
                    }
                    item { NeoViewAllTile(focused = active && row == 1 && recentCol == recent.size) }
                }
            }
        }
    }
}
