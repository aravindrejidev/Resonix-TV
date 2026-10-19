package com.resonix.tv.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
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
import com.resonix.tv.data.MediaEntry
import com.resonix.tv.data.MediaLibrary
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.formatSize
import com.resonix.tv.ui.formatTime
import com.resonix.tv.ui.theme.NeoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface HomeRow {
    data object Browse : HomeRow
    data object Grant : HomeRow
    data object Diagnostics : HomeRow
    data class Video(val entry: MediaEntry) : HomeRow
}

/** Start screen: browse files, or pick one of the videos Android has indexed (internal + USB). */
@Composable
fun HomeScreen(onPlay: (Uri, String) -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val currentOnPlay by rememberUpdatedState(onPlay)
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
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { picked: Uri? ->
        if (picked != null) currentOnPlay(picked, MediaLibrary.displayName(context, picked))
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
            add(HomeRow.Diagnostics)
            videos.forEach { add(HomeRow.Video(it)) }
        }
    }
    val latestRows by rememberUpdatedState(rows)

    LaunchedEffect(rows.size) { cursor = cursor.coerceIn(0, rows.lastIndex) }

    val activate: (HomeRow) -> Unit = { row ->
        when (row) {
            HomeRow.Browse -> {
                runCatching { pickerLauncher.launch(arrayOf("video/*", "audio/*")) }.onFailure {
                    Toast.makeText(
                        context,
                        "This TV has no file picker. Open a file from a file manager and choose ResonixTV.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            HomeRow.Grant -> runCatching { permissionLauncher.launch(MediaLibrary.permissions()) }
            HomeRow.Diagnostics -> currentOnDiagnostics()
            is HomeRow.Video -> currentOnPlay(row.entry.uri, row.entry.title)
        }
    }
    val latestActivate by rememberUpdatedState(activate)

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, _ ->
            when (key) {
                RemoteKey.UP -> {
                    cursor = (cursor - 1).coerceAtLeast(0)
                    true
                }
                RemoteKey.DOWN -> {
                    cursor = (cursor + 1).coerceAtMost(latestRows.lastIndex)
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
        videos.isEmpty() -> "No videos found. Use Browse files, or open a file from a file manager."
        else -> "${videos.size} videos found"
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
                    HomeRow.Browse -> NeoRow("Browse files...", "Pick a video or audio file from USB or internal storage", focused)
                    HomeRow.Grant -> NeoRow("Allow access to media", "Needed to list the videos on this TV and on USB drives", focused)
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
