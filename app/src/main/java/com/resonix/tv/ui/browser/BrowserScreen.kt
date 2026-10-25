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
import com.resonix.tv.data.MediaLibrary
import com.resonix.tv.data.StorageRoot
import com.resonix.tv.data.StorageRoots
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
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
 * Built-in file browser (many TVs have no system file picker): storage devices, folders, media files.
 *   UP / DOWN move (hold = faster)    OK or RIGHT open / play    LEFT or BACK one level up
 */
@Composable
fun BrowserScreen(startDir: File?, onPlay: (PlayRequest) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("resonix_ui", Context.MODE_PRIVATE) }
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnExit by rememberUpdatedState(onExit)

    var hasPermission by remember { mutableStateOf(MediaLibrary.hasPermission(context)) }
    var allFiles by remember { mutableStateOf(allFilesAccess()) }
    var roots by remember { mutableStateOf<List<StorageRoot>>(emptyList()) }
    var dir by remember {
        mutableStateOf(
            startDir?.takeIf { it.isDirectory }
                ?: prefs.getString("last_dir", null)?.let { File(it) }?.takeIf { it.isDirectory },
        )
    }
    var entries by remember { mutableStateOf<List<BrowserEntry>?>(emptyList()) }
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
        while (true) {
            delay(1500)
            hasPermission = MediaLibrary.hasPermission(context)
            allFiles = allFilesAccess()
        }
    }

    LaunchedEffect(dir, hasPermission, allFiles) {
        roots = withContext(Dispatchers.IO) { StorageRoots.list(context) }
        val folder = dir
        if (folder == null) {
            entries = emptyList()
        } else {
            loading = true
            entries = withContext(Dispatchers.IO) { FileBrowser.list(folder) }
            loading = false
            prefs.edit().putString("last_dir", folder.path).apply()
        }
    }
    LaunchedEffect(dir) { cursor = cursorMemory[dir?.path ?: ""] ?: 0 }

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
    LaunchedEffect(rows.size) { cursor = cursor.coerceIn(0, (rows.size - 1).coerceAtLeast(0)) }

    fun enter(target: File?) {
        cursorMemory[dir?.path ?: ""] = cursor
        dir = target
    }

    fun goUp() {
        val folder = dir
        if (folder == null) {
            currentOnExit()
            return
        }
        val parent = folder.parentFile
        val underRoot = roots.any { folder.path.startsWith(it.path.path) }
        val atRoot = parent == null || !underRoot || roots.any { it.path == folder }
        enter(if (atRoot) null else parent)
    }

    fun play(file: File) {
        scope.launch {
            val subtitles = withContext(Dispatchers.IO) { FileBrowser.findSubtitles(file) }
            currentOnPlay(PlayRequest(Uri.fromFile(file), file.nameWithoutExtension, subtitles))
        }
    }

    fun activate(row: BrowserRow) {
        when (row) {
            BrowserRow.Grant -> runCatching { permissionLauncher.launch(MediaLibrary.permissions()) }
            BrowserRow.AllFiles -> openAllFilesSettings(context)
            is BrowserRow.Volume -> enter(row.root.path)
            is BrowserRow.Item -> if (row.entry.isDir) enter(row.entry.file) else play(row.entry.file)
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
                    latestRows.getOrNull(cursor)?.let { activate(it) }
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
    val heading = if (folder == null) "Storage devices" else StorageRoots.prettyPath(roots, folder)
    val status = when {
        loading -> "Loading..."
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
            NeoText("Browse files", size = 36.sp, color = NeoColors.Cyan, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            NeoText(heading, size = 16.sp, color = NeoColors.TextPrimary, maxLines = 4)
            Spacer(Modifier.height(14.dp))
            NeoText(status, size = 14.sp, color = NeoColors.TextDim, maxLines = 4)
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
                        title = row.entry.file.name,
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
