package com.resonix.tv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.resonix.tv.diag.AppLog
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A server that answers on the SMB port. [name] is the host name when the network told it, otherwise the same as [host]. */
class FoundServer(val name: String, val host: String)

/**
 * Looks for SMB servers on the local network, in two ways at the same time:
 *  1. Network service discovery (mDNS, "_smb._tcp"): NAS boxes usually announce themselves, with a nice name.
 *  2. A quick check of the TV's own /24 network for an open port 445: also finds Windows PCs, which do not announce.
 * The flow reports servers as they appear and never ends by itself: stop collecting when done
 * (that also stops the discovery).
 */
object SmbDiscovery {
    private const val SMB_PORT = 445
    private const val PROBE_TIMEOUT_MS = 600
    private const val PARALLEL_PROBES = 40

    fun discover(context: Context): Flow<FoundServer> = callbackFlow {
        val app = context.applicationContext
        val reported = ConcurrentHashMap<String, Boolean>()
        fun report(name: String, host: String) {
            if (reported.put("$name|$host", true) == null) trySend(FoundServer(name, host))
        }

        // 1) announcements (NSD)
        val manager = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val waiting = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        // NSD can resolve one service at a time: keep a small queue.
        fun resolveNext() {
            val next: NsdServiceInfo? = synchronized(waiting) {
                if (resolving || waiting.isEmpty()) {
                    null
                } else {
                    resolving = true
                    waiting.removeFirst()
                }
            }
            if (next == null) return
            fun finished() {
                synchronized(waiting) { resolving = false }
                resolveNext()
            }
            try {
                manager?.resolveService(
                    next,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            finished()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val address = serviceInfo.host
                            if (address is Inet4Address) {
                                val ip = address.hostAddress
                                if (ip != null) report(serviceInfo.serviceName, ip)
                            }
                            finished()
                        }
                    },
                )
            } catch (e: Exception) {
                finished()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.log("SMB_DISCOVERY", "announcement search could not start (code $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(waiting) { waiting.add(serviceInfo) }
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        var nsdStarted = false
        try {
            manager?.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            nsdStarted = manager != null
        } catch (e: Exception) {
            AppLog.log("SMB_DISCOVERY", "announcement search failed: ${e.javaClass.simpleName}")
        }

        // 2) port check of the local network
        launch(Dispatchers.IO) {
            try {
                val gate = Semaphore(PARALLEL_PROBES)
                localHosts(app).map { ip ->
                    async(Dispatchers.IO) {
                        gate.withPermit { if (portOpen(ip)) report(ip, ip) }
                    }
                }.awaitAll()
            } catch (e: Exception) {
                AppLog.log("SMB_DISCOVERY", "port check failed: ${e.javaClass.simpleName}")
            }
        }

        awaitClose {
            if (nsdStarted) {
                try {
                    manager?.stopServiceDiscovery(discoveryListener)
                } catch (e: Exception) {
                    // already stopped
                }
            }
        }
    }

    /** The other 253 addresses of the /24 network the TV is on (empty when it is not on a network). */
    private fun localHosts(context: Context): List<String> {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val network = connectivity.activeNetwork ?: return emptyList()
        val properties = connectivity.getLinkProperties(network) ?: return emptyList()
        for (linkAddress in properties.linkAddresses) {
            val address = linkAddress.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                val bytes = address.address
                val own = bytes[3].toInt() and 255
                val prefix = (bytes[0].toInt() and 255).toString() + "." +
                    (bytes[1].toInt() and 255) + "." + (bytes[2].toInt() and 255) + "."
                val result = ArrayList<String>()
                for (last in 1..254) {
                    if (last != own) result.add(prefix + last)
                }
                return result
            }
        }
        return emptyList()
    }

    private fun portOpen(ip: String): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(ip, SMB_PORT), PROBE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
