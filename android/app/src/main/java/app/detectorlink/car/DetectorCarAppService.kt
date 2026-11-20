package app.detectorlink.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import app.detectorlink.graph
import app.detectorlink.net.LinkStatus
import app.detectorlink.service.DetectionService

class DetectorCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = DetectorSession()
}

class DetectorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        reconnectIfIdle()
        return ActiveDetectionsScreen(carContext)
    }

    private fun reconnectIfIdle() {
        val g = carContext.graph
        if (g.connection.status.value.phase != LinkStatus.Phase.IDLE) return
        val last = g.lastDetector.load() ?: return
        try {
            DetectionService.start(carContext, last)
        } catch (e: IllegalStateException) {
            Log.w("DetectorSession", "could not start service from car session", e)
        }
    }
}
