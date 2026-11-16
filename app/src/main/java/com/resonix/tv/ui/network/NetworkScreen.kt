package com.resonix.tv.ui.network

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.net.SmbConnections
import com.resonix.tv.net.SmbErrors
import com.resonix.tv.net.SmbShare
import com.resonix.tv.net.SmbShareStore
import com.resonix.tv.ui.browser.BrowseStart
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.theme.NeoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface NetPage {
    data object Shares : NetPage
    data class Options(val id: String) : NetPage
    data class Picker(val id: String) : NetPage
}

private class NetRow(
    val title: String,
    val subtitle: String?,
    val icon: NeoIcon,
    val marker: String? = null,
    /** RIGHT key. Null = same as OK. */
    val onMore: (() -> Unit)? = null,
    val onSelect: () -> Unit,
)

/**
 * The saved network shares. OK opens a share, RIGHT offers Edit / Delete, "Add share" makes a new one.
 * A share saved without a share name first shows the share names found on the server.
 */
@Composable
fun NetworkScreen(
    onAddShare: () -> Unit,
    onEditShare: (String) -> Unit,
    onOpenShare: (BrowseStart.Smb) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SmbShareStore.get(context) }
    val shares = store.shares
    val currentOnAdd by rememberUpdatedState(onAddShare)
    val currentOnEdit by rememberUpdatedState(onEditShare)
    val currentOnOpen by rememberUpdatedState(onOpenShare)
    val currentOnExit by rememberUpdatedState(onExit)

    var page by remember { mutableStateOf<NetPage>(NetPage.Shares) }
    var cursor by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickerBusy by remember { mutableStateOf(false) }
    var pickerNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickerMessage by remember { mutableStateOf("") }

    fun showPage(target: NetPage) {
        page = target
        cursor = 0
        confirmDelete = false
    }

    fun loadPicker(share: SmbShare) {
        pickerBusy = true
        pickerNames = emptyList()
        pickerMessage = ""
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { SmbConnections.probeShares(store.credentialsOf(share, "")) }
            }
            pickerBusy = false
            val names = outcome.getOrNull()
            if (names == null) {
                pickerMessage = outcome.exceptionOrNull()?.let { SmbErrors.describe(it) } ?: "The server could not be asked."
            } else {
                pickerNames = names
                pickerMessage = if (names.isEmpty()) {
                    "None of the usual share names was found. Edit this share and type the share name."
                } else {
                    "These are the usual share names that exist on the server. If yours is missing, edit this share and type its name."
                }
            }
        }
    }

    fun openShare(share: SmbShare) {
        if (share.shareName.isNotEmpty()) {
            currentOnOpen(BrowseStart.Smb(share.id, share.shareName, ""))
        } else {
            showPage(NetPage.Picker(share.id))
            loadPicker(share)
        }
    }

    val current = page
    val rows: List<NetRow> = when (current) {
        NetPage.Shares -> buildList<NetRow> {
            for (share in shares) {
                add(
                    NetRow(
                        title = share.displayName,
                        subtitle = if (share.shareName.isEmpty()) "${share.host}  |  choose a share" else "${share.host} / ${share.shareName}",
                        icon = NeoIcon.NETWORK,
                        marker = "OPEN",
                        onMore = { showPage(NetPage.Options(share.id)) },
                        onSelect = { openShare(share) },
                    ),
                )
            }
            add(NetRow("Add share", "A NAS, a PC or a Mac on your network", NeoIcon.PLUS, onSelect = { currentOnAdd() }))
        }
        is NetPage.Options -> {
            val share = store.find(current.id)
            if (share == null) {
                emptyList()
            } else {
                listOf(
                    NetRow("Open", share.displayName, NeoIcon.FOLDER, onSelect = { openShare(share) }),
                    NetRow("Edit", "Change the address, share, user or password", NeoIcon.SETTINGS, onSelect = { currentOnEdit(share.id) }),
                    NetRow(
                        title = if (confirmDelete) "Press OK again to delete" else "Delete",
                        subtitle = "Removes this share and its saved password from the TV",
                        icon = NeoIcon.INFO,
                        onSelect = {
                            if (confirmDelete) {
                                store.delete(share.id)
                                showPage(NetPage.Shares)
                            } else {
                                confirmDelete = true
                            }
                        },
                    ),
                )
            }
        }
        is NetPage.Picker -> {
            val share = store.find(current.id)
            if (share == null) {
                emptyList()
            } else {
                buildList<NetRow> {
                    for (name in pickerNames) {
                        add(NetRow(name, "Share on ${share.host}", NeoIcon.FOLDER, marker = "OPEN", onSelect = { currentOnOpen(BrowseStart.Smb(share.id, name, "")) }))
                    }
                    if (!pickerBusy) add(NetRow("Search again", null, NeoIcon.UPDATE, onSelect = { loadPicker(share) }))
                }
            }
        }
    }
    val latestRows by rememberUpdatedState(rows)
    LaunchedEffect(rows.size) { cursor = cursor.coerceIn(0, (rows.size - 1).coerceAtLeast(0)) }

    fun goBack() {
        if (page == NetPage.Shares) currentOnExit() else showPage(NetPage.Shares)
    }

    BackHandler { goBack() }

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, _ ->
            val all = latestRows
            when (key) {
                RemoteKey.UP -> {
                    cursor = (cursor - 1).coerceAtLeast(0)
                    true
                }
                RemoteKey.DOWN -> {
                    cursor = (cursor + 1).coerceAtMost((all.size - 1).coerceAtLeast(0))
                    true
                }
                RemoteKey.SELECT -> {
                    all.getOrNull(cursor)?.let { it.onSelect() }
                    true
                }
                RemoteKey.RIGHT -> {
                    val row = all.getOrNull(cursor)
                    if (row != null) {
                        val more = row.onMore
                        if (more != null) more() else row.onSelect()
                    }
                    true
                }
                RemoteKey.LEFT -> {
                    goBack()
                    true
                }
                else -> false
            }
        }
        KeyRouter.handler = handler
        onDispose { if (KeyRouter.handler === handler) KeyRouter.handler = null }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(cursor, rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem((cursor - 2).coerceAtLeast(0))
    }

    val heading = when (current) {
        NetPage.Shares -> "Saved shares"
        is NetPage.Options -> store.find(current.id)?.displayName ?: "Share"
        is NetPage.Picker -> "Shares on " + (store.find(current.id)?.host ?: "the server")
    }
    val status = when (current) {
        NetPage.Shares ->
            if (shares.isEmpty()) "No saved shares yet. Choose Add share to connect to your NAS or PC." else "${shares.size} saved"
        is NetPage.Options -> if (confirmDelete) "Press OK once more to remove it, or BACK to keep it." else "Choose what to do."
        is NetPage.Picker -> if (pickerBusy) "Looking for shares on the server..." else pickerMessage
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
            NeoText("Network", size = 36.sp, color = NeoColors.Cyan, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            NeoText(heading, size = 16.sp, color = NeoColors.TextPrimary, maxLines = 3)
            Spacer(Modifier.height(14.dp))
            NeoText(status, size = 14.sp, color = NeoColors.TextDim, maxLines = 9)
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NeoChip("UP / DOWN  choose")
                NeoChip("OK  open")
                if (page == NetPage.Shares) NeoChip("RIGHT  edit or delete")
                NeoChip("LEFT or BACK  go back")
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
        ) {
            itemsIndexed(rows) { index, row ->
                NeoRow(
                    title = row.title,
                    subtitle = row.subtitle,
                    focused = index == cursor,
                    marker = row.marker,
                    icon = row.icon,
                )
            }
        }
    }
}
