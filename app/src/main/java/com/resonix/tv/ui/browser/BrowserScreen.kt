package com.resonix.tv.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.data.BrowserEntry
import com.resonix.tv.data.FileBrowser
import com.resonix.tv.data.FileSource
import com.resonix.tv.data.LocalFileSource
import com.resonix.tv.data.MediaLibrary
import com.resonix.tv.data.SmbFileSource
import com.resonix.tv.data.StorageRoot
import com.resonix.tv.data.StorageRoots
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.net.SmbErrors
import com.resonix.tv.net.SmbProblem
import com.resonix.tv.net.SmbShareStore
import com.resonix.tv.player.PlayRequest
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.formatSize
import com.resonix.tv.ui.theme.NeoColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the browser opens on: this TV's storage, or a folder of a saved network share. */
sealed interface BrowseStart {
    /** [dir] null = the list of storage devices. */
    data class Local(val dir: File?) : BrowseStart

    /** [shareName] is the share to open on the saved server [shareId]; [path] "" = its top folder. */
    data class Smb(val shareId: String, val shareName: String, val path: String = "") : BrowseStart
}

/** The source to read from, or the reason it can not be opened; [title] is the heading of a network share. */
private class OpenedSource(val source: FileSource?, val error: String?, val title: String)

private fun openSource(context: Context, start: BrowseStart): OpenedSource = when (start) {
    is BrowseStart.Local -> OpenedSource(LocalFileSource, null, "")
    is BrowseStart.Smb -> {
        val store = SmbShareStore.get(context)
        val share = store.find(start.shareId)
        if (share == null) {
            OpenedSource(null, SmbProblem.NOT_SAVED.text, "Network")
        } else {
            try {
                OpenedSource(SmbFileSource(store.credentialsOf(share, start.shareName)), null, share.displayName + " / " + start.shareName)
            } catch (e: Exception) {
                OpenedSource(null, SmbErrors.describe(e), share.displayName)
            }
        }
    }
}

/** Last folder shown per network share: coming back from the player returns to the same folder. */
private val lastSmbFolder = HashMap<String, String>()

private fun smbKey(start: BrowseStart.Smb): String = start.shareId + "|" + start.shareName

private sealed interface BrowserRow {
    data object Grant : BrowserRow
    data object AllFiles : BrowserRow
    data class Volume(val root: StorageRoot) : BrowserRow
    data class Item(val entry: BrowserEntry) : BrowserRow
}

private fun allFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

private fun openAllFilesSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val opened =
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
        }.isSuccess ||
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.isSuccess
    if (!opened) {
        Toast.makeText(
            context,
            "This TV has no such settings page. With a computer run: adb shell appops set com.resonix.tv MANAGE_EXTERNAL_STORAGE allow",
            Toast.LENGTH_LONG,
        ).show()
    }
}

/**
 * Built-in file browser (many TVs have no system file picker): storage devices or a network share,
 * folders, media files. The same screen serves both: it only talks to a [FileSource].
 *   UP / DOWN move (hold = faster)    OK or RIGHT open / play    LEFT or BACK one level up
 */
@Composable
fun BrowserScreen(start: BrowseStart, onPlay: (PlayRequest) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("resonix_ui", Context.MODE_PRIVATE) }
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnExit by rememberUpdatedState(onExit)

    val network = start is BrowseStart.Smb
    val opened = remember(start) { openSource(context, start) }
    val source = opened.source

    var hasPermission by remember { mutableStateOf(MediaLibrary.hasPermission(context)) }
    var allFiles by remember { mutableStateOf(allFilesAccess()) }
    var roots by remember { mutableStateOf<List<StorageRoot>>(emptyList()) }
    // Folder shown now. Local: a full path, null = the list of storage devices. Network: the path
    // inside the share, "" = the top of the share.
    var dir by remember {
        mutableStateOf<String?>(
            when (start) {
                is BrowseStart.Local ->
                    start.dir?.takeIf { it.isDirectory }?.path
                        ?: prefs.getString("last_dir", null)?.takeIf { File(it).isDirectory }
                is BrowseStart.Smb -> lastSmbFolder[smbKey(start)] ?: start.path
            },
        )
    }
    var entries by remember { mutableStateOf<List<BrowserEntry>?>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var cursor by remember { mutableIntStateOf(0) }
    val cursorMemory = remember { HashMap<String, Int>() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermission = MediaLibrary.hasPermission(context)
    }

    // The permission pages are separate Android screens: notice when the user comes back.
    LaunchedEffect(Unit) {
        if (network) return@LaunchedEffect
        while (true) {
            delay(1500)
            hasPermission = MediaLibrary.hasPermission(context)
            allFiles = allFilesAccess()
        }
    }

    LaunchedEffect(dir, hasPermission, allFiles, reloadTick) {
        if (!network) roots = withContext(Dispatchers.IO) { StorageRoots.list(context) }
        val folder = dir
        if (folder == null || source == null) {
            entries = emptyList()
        } else {
            loading = true
            listError = null
            // A slow server: do not leave the old folder's lines on screen under the new heading.
            if (network) entries = emptyList()
            val outcome = withContext(Dispatchers.IO) { runCatching { FileBrowser.list(source, folder) } }
            entries = outcome.getOrNull()
            listError = outcome.exceptionOrNull()?.let { SmbErrors.describe(it) }
            loading = false
            if (start is BrowseStart.Smb) lastSmbFolder[smbKey(start)] = folder
            if (!network) prefs.edit().putString("last_dir", folder).apply()
        }
    }
    LaunchedEffect(dir) { cursor = cursorMemory[dir ?: ""] ?: 0 }

    val rows: List<BrowserRow> = remember(dir, roots, entries, hasPermission, allFiles) {
        buildList<BrowserRow> {
            if (dir == null) {
                if (!hasPermission) add(BrowserRow.Grant)
                if (Build.VERSION.SDK_INT >= 30 && !allFiles) add(BrowserRow.AllFiles)
                roots.forEach { add(BrowserRow.Volume(it)) }
            } else {
                entries?.forEach { add(BrowserRow.Item(it)) }
            }
        }
    }
    val latestRows by rememberUpdatedState(rows)
    LaunchedEffect(rows.size) { if (!loading) cursor = cursor.coerceIn(0, (rows.size - 1).coerceAtLeast(0)) }

    fun enter(target: String?) {
        cursorMemory[dir ?: ""] = cursor
        dir = target
    }

    fun goUp() {
        val folder = dir
        if (folder == null) {
            currentOnExit()
            return
        }
        if (network) {
            // Top of the share: back to the list of network shares.
            if (folder.isEmpty()) currentOnExit() else enter(source?.parent(folder) ?: "")
            return
        }
        val parent = File(folder).parentFile
        val underRoot = roots.any { folder.startsWith(it.path.path) }
        val atRoot = parent == null || !underRoot || roots.any { it.path.path == folder }
        enter(if (atRoot) null else parent?.path)
    }

    fun play(entry: BrowserEntry) {
        val src = source ?: return
        val folder = dir ?: return
        scope.launch {
            val subtitles = withContext(Dispatchers.IO) { FileBrowser.findSubtitles(src, folder, entry.name) }
            currentOnPlay(PlayRequest(src.uriOf(folder, entry.name), entry.name.substringBeforeLast('.'), subtitles))
        }
    }

    fun activate(row: BrowserRow) {
        when (row) {
            BrowserRow.Grant -> runCatching { permissionLauncher.launch(MediaLibrary.permissions()) }
            BrowserRow.AllFiles -> openAllFilesSettings(context)
            is BrowserRow.Volume -> enter(row.root.path.path)
            is BrowserRow.Item -> if (row.entry.isDir) enter(row.entry.path) else play(row.entry)
        }
    }

    BackHandler { goUp() }

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, repeat ->
            val step = if (repeat > 10) 5 else 1
            when (key) {
                RemoteKey.UP -> {
                    cursor = (cursor - step).coerceAtLeast(0)
                    true
                }
                RemoteKey.DOWN -> {
                    cursor = (cursor + step).coerceAtMost((latestRows.size - 1).coerceAtLeast(0))
                    true
                }
                RemoteKey.LEFT -> {
                    goUp()
                    true
                }
                RemoteKey.RIGHT, RemoteKey.SELECT -> {
                    val row = latestRows.getOrNull(cursor)
                    if (row != null) activate(row) else if (network && !loading) reloadTick++
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

    val folder = dir
    val listing = entries
    val heading = when {
        network -> if (folder.isNullOrEmpty()) opened.title else opened.title + " / " + folder.replace("/", " / ")
        folder == null -> "Storage devices"
        else -> StorageRoots.prettyPath(roots, File(folder))
    }
    val status = when {
        opened.error != null -> opened.error
        loading -> "Loading..."
        network && listError != null -> listError + "\nPress OK to try again."
        folder == null && roots.isEmpty() -> "No storage found."
        folder == null -> "Choose a storage device. USB drives appear here when plugged in."
        listing == null -> "This folder cannot be read. Allow access to files first."
        listing.isEmpty() -> "No folders or media files here."
        else -> "${listing.size} items"
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
            NeoText(if (network) "Network files" else "Browse files", size = 36.sp, color = NeoColors.Cyan, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            NeoText(heading, size = 16.sp, color = NeoColors.TextPrimary, maxLines = 4)
            Spacer(Modifier.height(14.dp))
            NeoText(status ?: "", size = 14.sp, color = if (network && listError != null) NeoColors.Warning else NeoColors.TextDim, maxLines = 8)
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NeoChip("UP / DOWN  choose")
                NeoChip("OK  open or play")
                NeoChip("LEFT or BACK  go up")
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
                    BrowserRow.Grant -> NeoRow("Allow access to files", "Needed to list folders and media files", focused, icon = NeoIcon.INFO)
                    BrowserRow.AllFiles -> NeoRow(
                        "Enable All files access",
                        "Optional: shows every file (for example subtitles). Opens Android settings",
                        focused,
                        icon = NeoIcon.SETTINGS,
                    )
                    is BrowserRow.Volume -> NeoRow(row.root.label, row.root.path.path, focused, icon = NeoIcon.DRIVE)
                    is BrowserRow.Item -> NeoRow(
                        title = row.entry.name,
                        subtitle = if (row.entry.isDir) "Folder" else formatSize(row.entry.sizeBytes),
                        focused = focused,
                        marker = if (row.entry.isDir) "OPEN" else null,
                        icon = if (row.entry.isDir) NeoIcon.FOLDER else NeoIcon.VIDEO,
                    )
                }
            }
        }
    }
}
