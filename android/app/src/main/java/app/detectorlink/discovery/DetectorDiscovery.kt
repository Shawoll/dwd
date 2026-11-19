package app.detectorlink.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Finds detectors on the local network via DNS-SD (`_cuas-detector._tcp`).
 *
 * Uses the platform NsdManager rather than a Java mDNS library:
 *  - the system mDNS daemon handles multicast, so we need no MulticastLock and
 *    don't fight other apps for port 5353;
 *  - it keeps working while the app is in the background.
 *
 * Known NsdManager traps handled here:
 *  - Callbacks arrive on a binder thread -> everything is funnelled through one
 *    channel and processed sequentially by one coroutine.
 *  - Before API 34, resolveService() allows only ONE resolve in flight
 *    (FAILURE_ALREADY_ACTIVE otherwise) -> resolves are serialised.
 *  - A resolve can hang on flaky Wi-Fi -> per-resolve timeout.
 */
class DetectorDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    private sealed interface NsdEvent {
        data class Found(val info: NsdServiceInfo) : NsdEvent
        data class Lost(val name: String) : NsdEvent
    }

    /** Emits the current list of resolved detectors. Discovery runs while collected. */
    fun discover(): Flow<List<DetectorEndpoint>> = callbackFlow {
        val events = Channel<NsdEvent>(Channel.UNLIMITED)
        val found = LinkedHashMap<String, DetectorEndpoint>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { events.trySend(NsdEvent.Found(info)) }
            override fun onServiceLost(info: NsdServiceInfo) { events.trySend(NsdEvent.Lost(info.serviceName)) }
            override fun onDiscoveryStarted(serviceType: String) { Log.d(TAG, "discovery started") }
            override fun onDiscoveryStopped(serviceType: String) { Log.d(TAG, "discovery stopped") }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("mDNS discovery failed to start ($errorCode)"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop discovery failed ($errorCode)")
            }
        }

        launch {
            send(emptyList())
            for (event in events) {
                when (event) {
                    is NsdEvent.Found -> resolve(event.info)?.let { found[it.name] = it }
                    is NsdEvent.Lost -> found.remove(event.name)
                }
                send(found.values.toList())
            }
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            events.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun resolve(info: NsdServiceInfo): DetectorEndpoint? = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            // resolveService is deprecated on API 34 in favour of registerServiceInfoCallback,
            // but it is the only option down to minSdk 26 and still works. A production
            // build would branch on SDK_INT and use the callback API on 34+.
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve ${info.serviceName} failed ($errorCode)")
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(info.toEndpoint())
                }
            })
        }
    }

    private fun NsdServiceInfo.toEndpoint(): DetectorEndpoint? {
        val address = preferredAddress() ?: return null
        fun txt(key: String) = attributes[key]?.toString(Charsets.UTF_8)
        // proto is the major protocol version; refuse detectors we can't talk to.
        val proto = txt("proto")?.toIntOrNull()
        if (proto != null && proto != app.detectorlink.model.Protocol.VERSION) {
            Log.w(TAG, "$serviceName speaks protocol $proto, app supports ${app.detectorlink.model.Protocol.VERSION}")
            return null
        }
        return DetectorEndpoint(
            name = serviceName,
            host = address.hostAddress ?: return null,
            port = port,
            id = txt("id"),
            wsPath = txt("ws") ?: "/ws",
            uiPath = txt("ui") ?: "/",
        )
    }

    /** IPv4 first: link-local IPv6 needs a scope id that OkHttp and WebView handle poorly. */
    private fun NsdServiceInfo.preferredAddress(): InetAddress? {
        val all: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
            hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(host)
        }
        return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
    }

    companion object {
        private const val TAG = "DetectorDiscovery"
        /** Must match detector-sim/src/mdns.rs (NsdManager wants it without ".local."). */
        const val SERVICE_TYPE = "_cuas-detector._tcp"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
