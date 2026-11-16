package com.resonix.tv.net

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import com.resonix.tv.diag.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * The saved network shares. Names and addresses are plain JSON; every password is stored apart,
 * encrypted by [SmbSecrets]. [shares] is observable by Compose.
 */
class SmbShareStore private constructor(private val prefs: SharedPreferences) {

    val shares = mutableStateListOf<SmbShare>()

    /** The same list as an unchanging copy, safe to read from background threads. */
    @Volatile
    private var snapshot: List<SmbShare> = emptyList()

    init {
        load()
    }

    fun find(id: String): SmbShare? = snapshot.firstOrNull { it.id == id }

    fun hasPassword(id: String): Boolean = prefs.contains(passwordKey(id))

    /** The saved password in clear text (for "Test connection" while editing), or null. */
    fun savedPassword(id: String): String? {
        val sealed = prefs.getString(passwordKey(id), null) ?: return null
        return SmbSecrets.decrypt(sealed)
    }

    /**
     * Saves a new or changed share. [newPassword] null = keep the stored password, "" = remove it.
     * Returns null on success, otherwise a sentence that says what went wrong.
     */
    fun save(share: SmbShare, newPassword: String?): String? {
        if (newPassword != null) {
            if (newPassword.isEmpty()) {
                prefs.edit().remove(passwordKey(share.id)).apply()
            } else {
                val sealed = SmbSecrets.encrypt(newPassword)
                    ?: return "This TV's secure storage is not working, so the password cannot be saved safely. " +
                        "Try Guest login, or restart the TV and try again."
                prefs.edit().putString(passwordKey(share.id), sealed).apply()
            }
        }
        val index = shares.indexOfFirst { it.id == share.id }
        if (index >= 0) shares[index] = share else shares.add(share)
        persist()
        SmbConnections.forget(share.host)
        AppLog.log("SMB_SHARE", "saved smb://${share.host}/${share.shareName}")
        return null
    }

    fun delete(id: String) {
        val old = find(id)
        shares.removeAll { it.id == id }
        prefs.edit().remove(passwordKey(id)).apply()
        persist()
        if (old != null) SmbConnections.forget(old.host)
    }

    /** Login data for a saved share, with [shareName] as the share to open. Throws SmbIoException if the password is unreadable. */
    fun credentialsOf(share: SmbShare, shareName: String = share.shareName): SmbCredentials {
        var password = ""
        if (!share.guest) {
            val sealed = prefs.getString(passwordKey(share.id), null)
            if (sealed != null) {
                password = SmbSecrets.decrypt(sealed) ?: throw SmbIoException(SmbProblem.PASSWORD_UNREADABLE)
            }
        }
        return SmbCredentials(share.host, share.port, shareName, share.username, password, share.domain, share.guest)
    }

    /**
     * Finds the saved share that belongs to a smb://host/share/... address (the address holds no
     * login data). Null when no saved share matches.
     */
    fun credentialsFor(host: String, port: Int, shareName: String): SmbCredentials? {
        val list = snapshot
        val match = list.firstOrNull { it.host.equals(host, true) && it.port == port && it.shareName.equals(shareName, true) }
            ?: list.firstOrNull { it.host.equals(host, true) && it.port == port && it.shareName.isEmpty() }
            ?: return null
        return credentialsOf(match, shareName)
    }

    private fun passwordKey(id: String) = "pw_$id"

    private fun load() {
        val text = prefs.getString("shares", null) ?: return
        runCatching {
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                shares.add(
                    SmbShare(
                        id = o.getString("id"),
                        displayName = o.optString("name"),
                        host = o.getString("host"),
                        port = o.optInt("port", 445),
                        shareName = o.optString("share"),
                        username = o.optString("user"),
                        domain = o.optString("domain"),
                        guest = o.optBoolean("guest", false),
                    ),
                )
            }
        }
        snapshot = shares.toList()
    }

    private fun persist() {
        val array = JSONArray()
        for (s in shares) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.displayName)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("share", s.shareName)
                    .put("user", s.username)
                    .put("domain", s.domain)
                    .put("guest", s.guest),
            )
        }
        prefs.edit().putString("shares", array.toString()).apply()
        snapshot = shares.toList()
    }

    companion object {
        @Volatile
        private var instance: SmbShareStore? = null

        fun get(context: Context): SmbShareStore =
            instance ?: synchronized(this) {
                instance ?: SmbShareStore(
                    context.applicationContext.getSharedPreferences("resonix_network", Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
