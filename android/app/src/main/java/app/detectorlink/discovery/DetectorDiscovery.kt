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

class DetectorDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    private sealed interface NsdEvent {
        data class Found(val info: NsdServiceInfo) : NsdEvent
        data class Lost(val name: String) : NsdEvent
    }

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
        // Before API 34 only one resolve may be in flight, so callers serialise these.
        suspendCancellableCoroutine { cont ->
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
        const val SERVICE_TYPE = "_cuas-detector._tcp"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
