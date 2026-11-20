package app.detectorlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

// The detector's AP has no internet, so Android keeps LTE as default. Bind the process to Wi-Fi.
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
