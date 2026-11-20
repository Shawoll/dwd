package app.detectorlink

import android.app.Application
import android.content.Context
import app.detectorlink.data.TrackStore
import app.detectorlink.discovery.DetectorDiscovery
import app.detectorlink.discovery.DetectorEndpoint
import app.detectorlink.net.DetectorConnection
import app.detectorlink.service.Alerts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DetectorLinkApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Alerts.createChannels(this)
    }
}

class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = TrackStore()

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    val connection = DetectorConnection(http, store, scope)
    val discovery = DetectorDiscovery(context)
    val lastDetector = LastDetector(context)
}

class LastDetector(context: Context) {
    private val prefs = context.getSharedPreferences("last_detector", Context.MODE_PRIVATE)

    fun save(e: DetectorEndpoint) = prefs.edit()
        .putString("name", e.name).putString("host", e.host).putInt("port", e.port)
        .putString("id", e.id).putString("ws", e.wsPath).putString("ui", e.uiPath)
        .apply()

    fun load(): DetectorEndpoint? {
        val host = prefs.getString("host", null) ?: return null
        return DetectorEndpoint(
            name = prefs.getString("name", host)!!,
            host = host,
            port = prefs.getInt("port", 8080),
            id = prefs.getString("id", null),
            wsPath = prefs.getString("ws", "/ws")!!,
            uiPath = prefs.getString("ui", "/")!!,
        )
    }
}

val Context.graph: AppGraph get() = (applicationContext as DetectorLinkApp).graph
