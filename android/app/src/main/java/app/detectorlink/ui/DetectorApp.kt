package app.detectorlink.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.detectorlink.AppGraph
import app.detectorlink.R
import app.detectorlink.data.DetectionState
import app.detectorlink.discovery.DetectorEndpoint
import app.detectorlink.model.Track
import app.detectorlink.net.LinkStatus
import app.detectorlink.service.Alerts
import app.detectorlink.service.Alerts.label
import app.detectorlink.service.DetectionService
import kotlinx.coroutines.flow.catch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectorApp(graph: AppGraph) {
    val context = LocalContext.current
    val link by graph.connection.status.collectAsStateWithLifecycle()
    val state by graph.store.state.collectAsStateWithLifecycle()
    var showWebUi by rememberSaveable { mutableStateOf(false) }
    val endpoint = link.endpoint

    if (showWebUi && endpoint != null) {
        DetectorWebView(url = endpoint.uiUrl, title = endpoint.name, onClose = { showWebUi = false })
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (link.phase == LinkStatus.Phase.IDLE) {
                DiscoveryPane(graph, onConnect = { DetectionService.start(context, it) })
            } else {
                LivePane(
                    link = link,
                    state = state,
                    onOpenUi = { showWebUi = true },
                    onDisconnect = { DetectionService.stop(context) },
                )
            }
        }
    }
}

@Composable
private fun DiscoveryPane(graph: AppGraph, onConnect: (DetectorEndpoint) -> Unit) {
    // Discovery runs only while this pane is on screen and the app is visible.
    val detectors by remember {
        graph.discovery.discover().catch { e ->
            Log.w("DiscoveryPane", "discovery failed", e)
            emit(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var manual by rememberSaveable { mutableStateOf("") }
    val manualEndpoint = DetectorEndpoint.parseManual(manual)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.discovery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        items(detectors, key = { it.name }) { d ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onConnect(d) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(d.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${d.host}:${d.port}" + (d.id?.let { " · $it" } ?: ""),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.manual_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    placeholder = { Text("192.168.4.1:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { manualEndpoint?.let(onConnect) }, enabled = manualEndpoint != null) {
                    Text(stringResource(R.string.connect))
                }
            }
        }
    }
}

@Composable
private fun LivePane(link: LinkStatus, state: DetectionState, onOpenUi: () -> Unit, onDisconnect: () -> Unit) {
    val tracks = state.sorted
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatusCard(link, state) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenUi, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.open_ui)) }
                OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
            }
        }
        item {
            Text(
                pluralStringResourceCompat(tracks.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (tracks.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_tracks),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(tracks, key = { it.id }) { TrackRow(it, stale = !state.synced) }
    }
}

@Composable
private fun StatusCard(link: LinkStatus, state: DetectionState) {
    val (label, color) = when (link.phase) {
        LinkStatus.Phase.LIVE -> stringResource(R.string.phase_live) to MaterialTheme.colorScheme.primary
        LinkStatus.Phase.CONNECTING -> stringResource(R.string.status_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
        LinkStatus.Phase.RECONNECTING -> stringResource(R.string.status_reconnecting) to MaterialTheme.colorScheme.error
        LinkStatus.Phase.IDLE -> stringResource(R.string.status_idle) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(
                    state.detector?.name ?: link.endpoint?.name.orEmpty(),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(label.uppercase(), color = color, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    link.endpoint?.let { "${it.host}:${it.port}" },
                    state.detector?.firmware,
                    "seq ${state.seq}".takeIf { state.seq >= 0 },
                ).joinToString(" · "),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (link.phase == LinkStatus.Phase.RECONNECTING && link.lastError != null) {
                Text(link.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TrackRow(t: Track, stale: Boolean) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(
                Modifier.width(4.dp).fillMaxHeight()
                    .background(t.threat.color(), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).weight(1f)) {
                Text(t.protocol, fontWeight = FontWeight.SemiBold)
                Text(
                    Alerts.describe(t) + " · ${t.freqMhz.toInt()} MHz",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                t.remoteId?.let {
                    Text("RID ${it.serial}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                if (stale) stringResource(R.string.stale) else t.threat.label(context).uppercase(),
                color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else t.threat.color(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 14.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.background)
}

@Composable
private fun pluralStringResourceCompat(count: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.active_tracks, count, count)
