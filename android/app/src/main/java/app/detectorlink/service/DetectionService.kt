package app.detectorlink.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.detectorlink.data.DetectionState
import app.detectorlink.discovery.DetectorEndpoint
import app.detectorlink.graph
import app.detectorlink.net.LinkStatus
import app.detectorlink.net.WifiBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the detector link alive while the app is in the background or the
 * screen is off - which is the normal state when the phone sits in a car mount.
 *
 * Why each piece is here:
 *  - Foreground service, type `connectedDevice`: the detector IS an external
 *    device we're connected to. FGS keeps network access under Doze and isn't
 *    subject to the 6 h daily cap Android 15 puts on `dataSync`.
 *  - Partial wake lock: alerts are safety-relevant and must not wait for the
 *    next maintenance window. Cost is acceptable because it's only held while
 *    connected, and in a car the phone is usually charging.
 *  - Wi-Fi lock: keeps the radio out of power-save so latency stays low.
 *  - WifiBinding: see that class; the detector's AP usually has no internet.
 */
class DetectionService : LifecycleService() {
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiBinding: WifiBinding
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        val g = graph
        foreground = goForeground()
        if (!foreground) return

        wifiBinding = WifiBinding(this).also { it.start() }
        @Suppress("DEPRECATION")
        val wifiMode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = getSystemService(WifiManager::class.java).createWifiLock(wifiMode, "detectorlink:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "detectorlink:link").apply {
                setReferenceCounted(false)
                acquire()
            }

        // Live status in the ongoing notification; at most once a second.
        lifecycleScope.launch {
            combine(g.connection.status, g.store.state) { link, state -> link to state }
                .conflate()
                .collect { (link, state) ->
                    notify(link, state)
                    delay(1_000)
                }
        }
        lifecycleScope.launch {
            g.connection.alerts.collect { Alerts.threat(this@DetectionService, it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!foreground || intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // intent is null when the system restarts us after killing the process.
        val endpoint = intent?.endpoint() ?: graph.lastDetector.load()
        if (endpoint == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        graph.lastDetector.save(endpoint)
        graph.connection.connect(endpoint)
        return START_STICKY
    }

    override fun onDestroy() {
        graph.connection.disconnect()
        if (::wifiBinding.isInitialized) wifiBinding.stop()
        if (::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(
            this, Alerts.ONGOING_ID,
            Alerts.ongoing(this, graph.connection.status.value, graph.store.state.value),
            type,
        )
        true
    } catch (e: Exception) {
        // Android 12+: a sticky restart from the background may not be allowed to
        // go foreground. Give up quietly; the user reconnects from the app.
        Log.w(TAG, "cannot start foreground", e)
        stopSelf()
        false
    }

    private fun notify(link: LinkStatus, state: DetectionState) {
        getSystemService(NotificationManager::class.java).notify(Alerts.ONGOING_ID, Alerts.ongoing(this, link, state))
    }

    companion object {
        private const val TAG = "DetectionService"
        private const val ACTION_STOP = "app.detectorlink.STOP"

        fun start(context: Context, endpoint: DetectorEndpoint) {
            ContextCompat.startForegroundService(context, Intent(context, DetectionService::class.java).putEndpoint(endpoint))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DetectionService::class.java))
        }

        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 1,
            Intent(context, DetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun Intent.putEndpoint(e: DetectorEndpoint) = putExtra("name", e.name)
            .putExtra("host", e.host).putExtra("port", e.port).putExtra("id", e.id)
            .putExtra("ws", e.wsPath).putExtra("ui", e.uiPath)

        private fun Intent.endpoint(): DetectorEndpoint? {
            val host = getStringExtra("host") ?: return null
            return DetectorEndpoint(
                name = getStringExtra("name") ?: host,
                host = host,
                port = getIntExtra("port", 8080),
                id = getStringExtra("id"),
                wsPath = getStringExtra("ws") ?: "/ws",
                uiPath = getStringExtra("ui") ?: "/",
            )
        }
    }
}
