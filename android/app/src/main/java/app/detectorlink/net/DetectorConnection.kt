package app.detectorlink.net

import android.os.SystemClock
import android.util.Log
import app.detectorlink.data.TrackStore
import app.detectorlink.discovery.DetectorEndpoint
import app.detectorlink.model.Protocol
import app.detectorlink.model.ServerMsg
import app.detectorlink.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class LinkStatus(
    val phase: Phase = Phase.IDLE,
    val endpoint: DetectorEndpoint? = null,
    val attempt: Int = 0,
    val lastError: String? = null,
) {
    enum class Phase { IDLE, CONNECTING, LIVE, RECONNECTING }
}

class DetectorConnection(
    private val client: OkHttpClient,
    private val store: TrackStore,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _alerts = MutableSharedFlow<Track>(extraBufferCapacity = 32)
    val alerts: SharedFlow<Track> = _alerts.asSharedFlow()

    private var job: Job? = null

    @Synchronized
    fun connect(endpoint: DetectorEndpoint) {
        if (job?.isActive == true && _status.value.endpoint == endpoint) return
        job?.cancel()
        if (_status.value.endpoint?.id != endpoint.id) store.clear()
        job = scope.launch { runLoop(endpoint) }
    }

    @Synchronized
    fun disconnect() {
        job?.cancel()
        job = null
        store.markStale()
        _status.value = LinkStatus()
    }

    private suspend fun runLoop(endpoint: DetectorEndpoint) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _status.value = LinkStatus(
                phase = if (attempt == 0) LinkStatus.Phase.CONNECTING else LinkStatus.Phase.RECONNECTING,
                endpoint = endpoint,
                attempt = attempt,
                lastError = _status.value.lastError,
            )
            val end = runSession(endpoint)
            store.markStale()
            Log.i(TAG, "session ended: $end")
            _status.value = _status.value.copy(phase = LinkStatus.Phase.RECONNECTING, lastError = end.reason)

            attempt = when {
                end is SessionEnd.Resync -> 0
                end.wasLive -> 1
                else -> attempt + 1
            }
            if (attempt > 0) delay(backoffMs(attempt))
        }
    }

    private sealed class SessionEnd(val reason: String, val wasLive: Boolean) {
        class Resync(reason: String) : SessionEnd(reason, true)
        class Closed(reason: String, wasLive: Boolean) : SessionEnd(reason, wasLive)
        override fun toString() = "${javaClass.simpleName}($reason)"
    }

    private suspend fun runSession(endpoint: DetectorEndpoint): SessionEnd = coroutineScope {
        val ended = CompletableDeferred<SessionEnd>()
        val lastFrameAt = AtomicLong(SystemClock.elapsedRealtime())
        val live = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastFrameAt.set(SystemClock.elapsedRealtime())
                val msg = Protocol.decode(text) ?: return
                val result = store.apply(msg)
                if (msg is ServerMsg.Snapshot && live.compareAndSet(false, true)) {
                    _status.value = LinkStatus(LinkStatus.Phase.LIVE, endpoint)
                }
                result.alerts.forEach { _alerts.tryEmit(it) }
                result.resyncReason?.let { reason ->
                    Log.w(TAG, "resync: $reason")
                    ended.complete(SessionEnd.Resync(reason))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(SessionEnd.Closed("closed by detector ($code)", live.get()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended.complete(SessionEnd.Closed(t.message ?: t.javaClass.simpleName, live.get()))
            }
        }

        val socket = client.newWebSocket(Request.Builder().url(endpoint.wsUrl).build(), listener)

        // Dead Wi-Fi often still looks alive to TCP.
        val watchdog = launch {
            while (isActive) {
                delay(1_000)
                if (live.get() && SystemClock.elapsedRealtime() - lastFrameAt.get() > STALE_MS) {
                    ended.complete(SessionEnd.Closed("no heartbeat for ${STALE_MS / 1000} s", true))
                }
            }
        }

        try {
            ended.await()
        } finally {
            watchdog.cancel()
            socket.cancel()
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val base = (500L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(15_000L)
        return (base * Random.nextDouble(0.5, 1.0)).toLong()
    }

    private companion object {
        const val TAG = "DetectorConnection"
        const val STALE_MS = 6_000L
    }
}
