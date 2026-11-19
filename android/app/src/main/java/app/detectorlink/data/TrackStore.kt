package app.detectorlink.data

import app.detectorlink.model.DetectorInfo
import app.detectorlink.model.ServerMsg
import app.detectorlink.model.ThreatLevel
import app.detectorlink.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DetectionState(
    val detector: DetectorInfo? = null,
    val tracks: Map<String, Track> = emptyMap(),
    val seq: Long = -1,
    /** False until the first snapshot, and again after the link drops (data is stale). */
    val synced: Boolean = false,
) {
    /** Most dangerous first, then closest. This is the order every UI shows. */
    val sorted: List<Track>
        get() = tracks.values.sortedWith(
            compareByDescending<Track> { it.threat }.thenBy { it.distanceM ?: Float.MAX_VALUE },
        )
}

data class ApplyResult(
    /** Non-null: client state is no longer trustworthy, reconnect for a fresh snapshot. */
    val resyncReason: String? = null,
    /** Tracks that are new, or whose threat level went up. These trigger alerts. */
    val alerts: List<Track> = emptyList(),
)

/**
 * Single source of truth for "what is in the air right now", shared by the
 * foreground service, the phone UI and the Android Auto screen.
 *
 * Pure Kotlin (no Android types), so the protocol rules are unit-tested on the JVM.
 * Messages for one socket arrive sequentially; @Synchronized covers the hand-over
 * between an old and a new socket.
 */
class TrackStore {
    private val _state = MutableStateFlow(DetectionState())
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    @Synchronized
    fun apply(msg: ServerMsg): ApplyResult {
        val s = _state.value
        return when (msg) {
            is ServerMsg.Hello -> {
                _state.value = s.copy(detector = msg.detector)
                ApplyResult()
            }

            is ServerMsg.Snapshot -> {
                val next = msg.tracks.associateBy { it.id }
                _state.value = s.copy(tracks = next, seq = msg.seq, synced = true)
                // After a reconnect only alert on what we didn't already know about.
                ApplyResult(alerts = next.values.filter { escalated(s.tracks[it.id], it) })
            }

            is ServerMsg.TrackUpdate -> delta(s, msg.seq) {
                val prev = s.tracks[msg.track.id]
                _state.value = s.copy(tracks = s.tracks + (msg.track.id to msg.track), seq = msg.seq)
                ApplyResult(alerts = listOfNotNull(msg.track.takeIf { escalated(prev, it) }))
            }

            is ServerMsg.TrackLost -> delta(s, msg.seq) {
                _state.value = s.copy(tracks = s.tracks - msg.id, seq = msg.seq)
                ApplyResult()
            }

            is ServerMsg.Heartbeat -> delta(s, msg.seq) {
                _state.value = s.copy(seq = msg.seq)
                if (msg.active != s.tracks.size) {
                    ApplyResult(resyncReason = "count mismatch: server ${msg.active}, local ${s.tracks.size}")
                } else {
                    ApplyResult()
                }
            }
        }
    }

    /** Link dropped: keep showing last known tracks, but flagged as stale. */
    @Synchronized
    fun markStale() {
        _state.value = _state.value.copy(synced = false)
    }

    @Synchronized
    fun clear() {
        _state.value = DetectionState()
    }

    private inline fun delta(s: DetectionState, seq: Long, block: () -> ApplyResult): ApplyResult {
        if (!s.synced) return ApplyResult(resyncReason = "delta before snapshot")
        if (seq != s.seq + 1) return ApplyResult(resyncReason = "seq gap ${s.seq} -> $seq")
        return block()
    }

    private fun escalated(prev: Track?, now: Track): Boolean =
        prev == null || now.threat > prev.threat
}
