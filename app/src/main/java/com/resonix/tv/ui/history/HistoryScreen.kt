package com.resonix.tv.ui.history

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.data.FileBrowser
import com.resonix.tv.data.HistoryStore
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.player.PlayRequest
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.formatTime
import com.resonix.tv.ui.theme.NeoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "View all" of Recently Watched: every movie in the history, newest first. */
@Composable
fun HistoryScreen(onPlay: (PlayRequest) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { HistoryStore.get(context) }
    val entries = history.entries
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnExit by rememberUpdatedState(onExit)
    var cursor by remember { mutableIntStateOf(0) }

    BackHandler { currentOnExit() }

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, repeat ->
            val step = if (repeat > 10) 5 else 1
            when (key) {
                RemoteKey.UP -> {
                    cursor = (cursor - step).coerceAtLeast(0)
                    true
                }
                RemoteKey.DOWN -> {
                    cursor = (cursor + step).coerceAtMost((entries.size - 1).coerceAtLeast(0))
                    true
                }
                RemoteKey.LEFT -> {
                    currentOnExit()
                    true
                }
                RemoteKey.SELECT, RemoteKey.RIGHT -> {
                    val entry = entries.getOrNull(cursor)
                    if (entry != null) {
                        scope.launch {
                            val uri = Uri.parse(entry.uri)
                            val subtitles = withContext(Dispatchers.IO) { FileBrowser.findSubtitlesFor(uri) }
                            currentOnPlay(PlayRequest(uri, entry.title, subtitles))
                        }
                    }
                    true
                }
                else -> false
            }
        }
        KeyRouter.handler = handler
        onDispose { if (KeyRouter.handler === handler) KeyRouter.handler = null }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(cursor) { if (entries.isNotEmpty()) listState.animateScrollToItem((cursor - 2).coerceAtLeast(0)) }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
            NeoText("Recently Watched", size = 34.sp, color = NeoColors.Cyan, weight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            NeoText(
                if (entries.isEmpty()) "Nothing here yet." else "${entries.size} movies",
                size = 15.sp,
                color = NeoColors.TextDim,
            )
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NeoChip("UP / DOWN  choose")
                NeoChip("OK  play from where you stopped")
                NeoChip("LEFT or BACK  return")
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
        ) {
            itemsIndexed(entries) { index, entry ->
                NeoRow(
                    title = entry.title,
                    subtitle = "${formatTime(entry.positionMs)} / ${formatTime(entry.durationMs)}   |   ${(entry.progress * 100).toInt()} %",
                    focused = index == cursor,
                    icon = NeoIcon.VIDEO,
                )
            }
        }
    }
}
