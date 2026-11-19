package app.detectorlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * The trap this solves: in the field the detector usually runs its own Wi-Fi
 * access point WITHOUT internet. Android then marks that Wi-Fi as "no internet"
 * and keeps mobile data as the default network, so a plain socket to
 * 192.168.x.x goes out over LTE and never arrives.
 *
 * Fix: track the Wi-Fi network explicitly (without requiring the INTERNET
 * capability) and bind the process to it while we're talking to the detector.
 * Binding the whole process also covers the WebView, which can't be given a
 * per-network socket factory. This app has no other traffic, so that's safe.
 */
class WifiBinding(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "binding process to Wi-Fi $network")
                cm.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                if (cm.boundNetworkForProcess == network) {
                    Log.i(TAG, "Wi-Fi $network lost, unbinding")
                    cm.bindProcessToNetwork(null)
                }
            }
        }
        cm.registerNetworkCallback(request, cb)
        callback = cb
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        cm.bindProcessToNetwork(null)
    }

    private companion object {
        const val TAG = "WifiBinding"
    }
}
