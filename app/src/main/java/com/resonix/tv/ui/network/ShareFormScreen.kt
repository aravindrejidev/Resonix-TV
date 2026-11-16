package com.resonix.tv.ui.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.input.KeyRouter
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.net.FoundServer
import com.resonix.tv.net.SmbConnections
import com.resonix.tv.net.SmbCredentials
import com.resonix.tv.net.SmbDiscovery
import com.resonix.tv.net.SmbInput
import com.resonix.tv.net.SmbShare
import com.resonix.tv.net.SmbShareStore
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoSectionHeader
import com.resonix.tv.ui.components.NeoSettingRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.theme.NeoColors
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class FormField { FIND, NAME, HOST, SHARE, GUEST, USER, PASSWORD, DOMAIN, TEST, SAVE }

/**
 * Add or edit a network share. Press OK on a line to type its value (a system keyboard box opens);
 * "Test connection" tries the login and says in plain words what is wrong; "Save" stores it.
 * The password is encrypted with the Android Keystore and is never shown again.
 */
@Composable
fun ShareFormScreen(editId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SmbShareStore.get(context) }
    val existing = remember(editId) { editId?.let { store.find(it) } }
    val currentOnDone by rememberUpdatedState(onDone)

    var name by remember { mutableStateOf(existing?.displayName ?: "") }
    var host by remember {
        mutableStateOf(existing?.let { if (it.port == 445) it.host else it.host + ":" + it.port } ?: "")
    }
    var shareName by remember { mutableStateOf(existing?.shareName ?: "") }
    var guest by remember { mutableStateOf(existing?.guest ?: false) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var passwordChanged by remember { mutableStateOf(false) }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }

    var message by remember { mutableStateOf("") }
    var messageGood by remember { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var cursor by remember { mutableIntStateOf(0) }

    // "Find servers": a list of what the network answered, shown instead of the form while it is open.
    var discovering by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchRun by remember { mutableIntStateOf(0) }
    var foundCursor by remember { mutableIntStateOf(0) }
    val found = remember { mutableStateListOf<FoundServer>() }
    val latestFound by rememberUpdatedState(found.toList())

    val fields: List<FormField> = remember(guest) {
        buildList<FormField> {
            add(FormField.FIND)
            add(FormField.NAME)
            add(FormField.HOST)
            add(FormField.SHARE)
            add(FormField.GUEST)
            if (!guest) {
                add(FormField.USER)
                add(FormField.PASSWORD)
                add(FormField.DOMAIN)
            }
            add(FormField.TEST)
            add(FormField.SAVE)
        }
    }
    val latestFields by rememberUpdatedState(fields)

    fun fail(text: String) {
        message = text
        messageGood = false
    }

    /** The share as typed, or null (and a message) when something is missing. */
    fun buildShare(): SmbShare? {
        val parts = SmbInput.parseHost(host)
        if (parts.host.isEmpty()) {
            fail("Type the server name or IP address first.")
            return null
        }
        val share = shareName.trim().ifEmpty { parts.impliedShare }
        val (user, userDomain) = SmbInput.splitUser(username, domain)
        if (!guest && user.isEmpty()) {
            fail("Type a user name, or switch on Guest login.")
            return null
        }
        val title = name.trim().ifEmpty { if (share.isEmpty()) parts.host else parts.host + " / " + share }
        return SmbShare(
            id = existing?.id ?: UUID.randomUUID().toString(),
            displayName = title,
            host = parts.host,
            port = parts.port,
            shareName = share,
            username = if (guest) "" else user,
            domain = if (guest) "" else userDomain,
            guest = guest,
        )
    }

    fun runTest() {
        if (testing) return
        val share = buildShare() ?: return
        val plainPassword = when {
            guest -> ""
            passwordChanged -> password
            existing != null -> store.savedPassword(existing.id) ?: ""
            else -> ""
        }
        val creds = SmbCredentials(
            share.host, share.port, share.shareName, share.username, plainPassword, share.domain, share.guest,
        )
        testing = true
        message = "Testing the connection..."
        messageGood = true
        scope.launch {
            val job = async(Dispatchers.IO) { SmbConnections.test(creds) }
            val outcome = withTimeoutOrNull(40_000L) { job.await() }
            testing = false
            if (outcome == null) {
                job.cancel()
                fail("The test took too long. The server may be off or out of reach.")
            } else {
                message = outcome.message
                messageGood = outcome.ok
            }
        }
    }

    fun save() {
        val share = buildShare() ?: return
        val newPassword: String? = when {
            guest -> ""
            passwordChanged || existing == null -> password
            else -> null
        }
        val problem = store.save(share, newPassword)
        if (problem != null) fail(problem) else currentOnDone()
    }

    fun prompt(title: String, initial: String, secret: Boolean, placeholder: String, onText: (String) -> Unit) {
        showTextPrompt(context, title, initial, secret, placeholder) { text -> onText(text.trim()) }
    }

    fun pickServer(server: FoundServer) {
        host = server.host
        if (name.isEmpty() && server.name != server.host) name = server.name
        discovering = false
        message = "Server chosen. Now check the share name and the login, then test."
        messageGood = true
    }

    fun activate(field: FormField) {
        when (field) {
            FormField.FIND -> {
                discovering = true
                searchRun++
            }
            FormField.NAME -> prompt("Display name", name, false, "For example Living room NAS") { name = it }
            FormField.HOST -> prompt("Server name or IP address", host, false, "For example 192.168.1.20") { host = it }
            FormField.SHARE -> prompt("Share name (optional)", shareName, false, "For example Movies") { shareName = it }
            FormField.GUEST -> guest = !guest
            FormField.USER -> prompt("User name", username, false, "The account on the server") { username = it }
            FormField.PASSWORD -> showTextPrompt(context, "Password", "", true, "Type the password") {
                password = it
                passwordChanged = true
            }
            FormField.DOMAIN -> prompt("Domain or workgroup (optional)", domain, false, "Usually empty") { domain = it }
            FormField.TEST -> runTest()
            FormField.SAVE -> save()
        }
    }

    BackHandler { if (discovering) discovering = false else currentOnDone() }

    // Searching: the flow ends when this effect is cancelled (page closed or search stopped).
    LaunchedEffect(discovering, searchRun) {
        if (!discovering) return@LaunchedEffect
        found.clear()
        foundCursor = 0
        searching = true
        try {
            withTimeoutOrNull(12_000L) {
                SmbDiscovery.discover(context).collect { server ->
                    val index = found.indexOfFirst { it.host == server.host }
                    if (index < 0) {
                        found.add(server)
                    } else if (found[index].name == found[index].host && server.name != server.host) {
                        found[index] = server // the same server again, now with its real name
                    }
                }
            }
        } finally {
            searching = false
        }
    }

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, _ ->
            val all = latestFields
            if (discovering) {
                val servers = latestFound
                when (key) {
                    RemoteKey.UP -> {
                        foundCursor = (foundCursor - 1).coerceAtLeast(0)
                        true
                    }
                    RemoteKey.DOWN -> {
                        foundCursor = (foundCursor + 1).coerceAtMost((servers.size - 1).coerceAtLeast(0))
                        true
                    }
                    RemoteKey.SELECT -> {
                        val server = servers.getOrNull(foundCursor)
                        if (server != null) pickServer(server) else if (!searching) searchRun++
                        true
                    }
                    RemoteKey.LEFT -> {
                        discovering = false
                        true
                    }
                    else -> false
                }
            } else when (key) {
                RemoteKey.UP -> {
                    cursor = (cursor - 1).coerceAtLeast(0)
                    true
                }
                RemoteKey.DOWN -> {
                    cursor = (cursor + 1).coerceAtMost(all.size - 1)
                    true
                }
                RemoteKey.SELECT -> {
                    all.getOrNull(cursor)?.let { activate(it) }
                    true
                }
                RemoteKey.LEFT, RemoteKey.RIGHT -> {
                    // A switch can also be flipped with LEFT / RIGHT.
                    if (all.getOrNull(cursor) == FormField.GUEST) guest = !guest
                    true
                }
                else -> false
            }
        }
        KeyRouter.handler = handler
        onDispose { if (KeyRouter.handler === handler) KeyRouter.handler = null }
    }

    LaunchedEffect(fields.size) { cursor = cursor.coerceIn(0, fields.size - 1) }
    val listState = rememberLazyListState()
    LaunchedEffect(cursor) { listState.animateScrollToItem((cursor - 3).coerceAtLeast(0)) }

    fun shown(text: String): String = if (text.isEmpty()) "not set" else if (text.length > 26) text.take(25) + "..." else text

    val passwordText = when {
        passwordChanged -> if (password.isEmpty()) "none" else "\u2022".repeat(minOf(password.length, 12))
        existing != null && store.hasPassword(existing.id) -> "saved"
        else -> "not set"
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NeoSectionHeader(
            title = if (existing == null) "Add share" else "Edit share",
            subtitle = "Press OK on a line to type it. Test the connection, then Save.",
            icon = NeoIcon.NETWORK,
        )
        if (discovering) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            ) {
                itemsIndexed(found) { index, server ->
                    NeoRow(
                        title = if (server.name == server.host) server.host else server.name,
                        subtitle = if (server.name == server.host) "Answers on the file sharing port" else server.host,
                        focused = index == foundCursor,
                        marker = "USE",
                        icon = NeoIcon.NETWORK,
                    )
                }
            }
            NeoText(
                text = when {
                    searching -> "Searching the network..."
                    found.isEmpty() -> "No server answered. Press OK to search again, or BACK to type the address yourself."
                    else -> "Choose your server with OK. BACK goes back to the form."
                },
                size = 16.sp,
                color = NeoColors.Cyan,
                maxLines = 2,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            ) {
                itemsIndexed(fields) { index, field ->
                    val focused = index == cursor
                    when (field) {
                        FormField.FIND -> NeoSettingRow("Find servers on the network", "Looks for NAS boxes and PCs that share files", NeoIcon.NETWORK, focused, chevron = true)
                        FormField.NAME -> NeoSettingRow("Display name", "How this share is called in the list", NeoIcon.INFO, focused, valueText = shown(name))
                        FormField.HOST -> NeoSettingRow("Server", "Name or IP address of the NAS or PC", NeoIcon.NETWORK, focused, valueText = shown(host))
                        FormField.SHARE -> NeoSettingRow("Share name (optional)", "Empty = look for the shares on the server", NeoIcon.FOLDER, focused, valueText = shown(shareName))
                        FormField.GUEST -> NeoSettingRow("Guest login", "For shares that need no user name or password", NeoIcon.PLAY, focused, toggle = guest)
                        FormField.USER -> NeoSettingRow("User name", "The account on the server", NeoIcon.INFO, focused, valueText = shown(username))
                        FormField.PASSWORD -> NeoSettingRow("Password", "Stored encrypted on this TV, never shown again", NeoIcon.SETTINGS, focused, valueText = passwordText)
                        FormField.DOMAIN -> NeoSettingRow("Domain (optional)", "Windows domain or workgroup, usually empty", NeoIcon.INFO, focused, valueText = shown(domain))
                        FormField.TEST -> NeoSettingRow(
                            title = if (testing) "Testing..." else "Test connection",
                            subtitle = "Tries the login and the share without saving",
                            icon = NeoIcon.UPDATE,
                            focused = focused,
                            chevron = true,
                        )
                        FormField.SAVE -> NeoSettingRow("Save", "Keep this share in the Network list", NeoIcon.PLUS, focused, chevron = true)
                    }
                }
            }
        }
        if (message.isNotEmpty()) {
            NeoText(message, size = 16.sp, color = if (messageGood) NeoColors.Cyan else NeoColors.Warning, maxLines = 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeoChip("UP / DOWN  choose")
            NeoChip("OK  type or run")
            NeoChip("BACK  cancel")
        }
        Spacer(Modifier.height(2.dp))
    }
}
