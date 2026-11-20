package app.detectorlink.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.detectorlink.R
import app.detectorlink.graph
import app.detectorlink.model.ThreatLevel
import app.detectorlink.model.Track
import app.detectorlink.net.LinkStatus
import app.detectorlink.service.Alerts
import app.detectorlink.service.Alerts.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class ActiveDetectionsScreen(carContext: CarContext) : Screen(carContext) {
    private val graph = carContext.graph

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(graph.store.state, graph.connection.status) { _, _ -> Unit }
                    .conflate()
                    .collect {
                        invalidate()
                        delay(1_000)
                    }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = graph.store.state.value
        val link = graph.connection.status.value
        val builder = ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_title)) // constant title = refresh, not a new step
            .setHeaderAction(Action.APP_ICON)

        if (link.phase == LinkStatus.Phase.CONNECTING && state.tracks.isEmpty()) {
            return builder.setLoading(true).build()
        }

        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val items = ItemList.Builder().setNoItemsMessage(emptyMessage(link))

        val stale = !state.synced
        state.sorted.take(limit).forEach { track -> items.addItem(row(track, stale)) }
        return builder.setSingleList(items.build()).build()
    }

    private fun row(t: Track, stale: Boolean): Row {
        val secondLine = if (stale) carContext.getString(R.string.car_stale) else Alerts.describe(t)
        return Row.Builder()
            .setTitle("${t.threat.label(carContext)} · ${t.protocol}")
            .addText(secondLine)
            .setImage(threatIcon(carContext, t.threat), Row.IMAGE_TYPE_ICON)
            .setOnClickListener { screenManager.push(TrackDetailScreen(carContext, t.id)) }
            .build()
    }

    private fun emptyMessage(link: LinkStatus): String = carContext.getString(
        when (link.phase) {
            LinkStatus.Phase.LIVE -> R.string.no_tracks
            LinkStatus.Phase.IDLE -> R.string.car_not_connected
            else -> R.string.status_reconnecting
        },
    )
}

internal fun threatIcon(context: CarContext, level: ThreatLevel): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(context, R.drawable.ic_drone))
        .setTint(
            when (level) {
                ThreatLevel.HIGH -> CarColor.RED
                ThreatLevel.MEDIUM -> CarColor.YELLOW
                ThreatLevel.LOW -> CarColor.GREEN
                ThreatLevel.UNKNOWN -> CarColor.DEFAULT
            },
        )
        .build()
