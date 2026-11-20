package app.detectorlink.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.detectorlink.R
import app.detectorlink.graph
import app.detectorlink.service.Alerts.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class TrackDetailScreen(carContext: CarContext, private val trackId: String) : Screen(carContext) {
    private val graph = carContext.graph

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.store.state.conflate().collect {
                    invalidate()
                    delay(1_000)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val t = graph.store.state.value.tracks[trackId]
        val pane = Pane.Builder()
        if (t == null) {
            pane.addRow(Row.Builder().setTitle(carContext.getString(R.string.car_track_lost)).build())
        } else {
            pane.setImage(threatIcon(carContext, t.threat))
            pane.addRow(row(carContext.getString(R.string.car_threat), "${t.threat.label(carContext)} · ${t.protocol}"))
            pane.addRow(
                row(
                    carContext.getString(R.string.car_position),
                    listOfNotNull(
                        t.bearingDeg?.let { "${it.toInt()}°" },
                        t.distanceM?.let { if (it >= 1000) "%.1f km".format(it / 1000) else "${it.toInt()} m" },
                    ).joinToString(" · ").ifEmpty { "—" },
                ),
            )
            pane.addRow(row(carContext.getString(R.string.car_signal), "${t.freqMhz.toInt()} MHz · ${t.rssiDbm.toInt()} dBm"))
            t.remoteId?.let { pane.addRow(row(carContext.getString(R.string.car_remote_id), it.serial)) }
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.car_detail_title))
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun row(title: String, text: String) = Row.Builder().setTitle(title).addText(text).build()
}
