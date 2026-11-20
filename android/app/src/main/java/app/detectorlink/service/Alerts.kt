package app.detectorlink.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.detectorlink.R
import app.detectorlink.car.DetectorCarAppService
import app.detectorlink.data.DetectionState
import app.detectorlink.model.ThreatLevel
import app.detectorlink.model.Track
import app.detectorlink.net.LinkStatus
import app.detectorlink.ui.MainActivity

object Alerts {
    private const val CHANNEL_LINK = "link"
    private const val CHANNEL_THREAT = "threat"
    const val ONGOING_ID = 1

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, context.getString(R.string.channel_link), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_THREAT, context.getString(R.string.channel_threat), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                description = context.getString(R.string.channel_threat_desc)
            },
        )
    }

    fun ongoing(context: Context, link: LinkStatus, state: DetectionState): Notification {
        val title = when (link.phase) {
            LinkStatus.Phase.LIVE -> context.getString(R.string.status_live, link.endpoint?.name ?: "")
            LinkStatus.Phase.CONNECTING -> context.getString(R.string.status_connecting)
            LinkStatus.Phase.RECONNECTING -> context.getString(R.string.status_reconnecting)
            LinkStatus.Phase.IDLE -> context.getString(R.string.status_idle)
        }
        val top = state.sorted.firstOrNull()
        val text = if (top == null) {
            context.getString(R.string.no_tracks)
        } else {
            context.resources.getQuantityString(R.plurals.active_tracks, state.tracks.size, state.tracks.size) +
                " · " + describe(top)
        }
        return NotificationCompat.Builder(context, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.disconnect), DetectionService.stopIntent(context))
            .build()
    }

    @SuppressLint("MissingPermission") // checked in canNotify()
    fun threat(context: Context, track: Track) {
        if (!canNotify(context)) return
        val title = context.getString(R.string.alert_title, track.threat.label(context))
        val text = "${track.protocol} · ${describe(track)}"

        val carIntent = CarPendingIntent.getCarApp(
            context,
            track.id.hashCode(),
            Intent(Intent.ACTION_VIEW).setComponent(ComponentName(context, DetectorCarAppService::class.java)),
            0,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_THREAT)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(openApp(context))
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .setContentIntent(carIntent)
                    .build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(track.id, 0, notification)
    }

    fun cancel(context: Context, trackId: String) =
        NotificationManagerCompat.from(context).cancel(trackId, 0)

    fun describe(t: Track): String = buildList {
        t.bearingDeg?.let { add("${it.toInt()}°") }
        t.distanceM?.let { add(if (it >= 1000) "%.1f km".format(it / 1000) else "${it.toInt()} m") }
        add("${t.rssiDbm.toInt()} dBm")
    }.joinToString(" · ")

    fun ThreatLevel.label(context: Context): String = context.getString(
        when (this) {
            ThreatLevel.HIGH -> R.string.threat_high
            ThreatLevel.MEDIUM -> R.string.threat_medium
            ThreatLevel.LOW -> R.string.threat_low
            ThreatLevel.UNKNOWN -> R.string.threat_unknown
        },
    )

    private fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
