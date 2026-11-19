package app.detectorlink.data

import app.detectorlink.model.Protocol
import app.detectorlink.model.ServerMsg
import app.detectorlink.model.ThreatLevel
import app.detectorlink.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStoreTest {

    private fun track(id: String, threat: ThreatLevel = ThreatLevel.LOW, distance: Float = 1500f) = Track(
        id = id, firstSeenMs = 0, lastSeenMs = 0, protocol = "DJI OcuSync 3",
        threat = threat, freqMhz = 5745f, rssiDbm = -80f, bearingDeg = 90f, distanceM = distance,
    )

    @Test
    fun `snapshot then contiguous deltas apply`() {
        val store = TrackStore()
        store.apply(ServerMsg.Snapshot(seq = 10, tsMs = 0, tracks = listOf(track("a"))))
        assertNull(store.apply(ServerMsg.TrackUpdate(11, track("b"))).resyncReason)
        assertNull(store.apply(ServerMsg.TrackLost(12, 0, "a")).resyncReason)

        assertEquals(setOf("b"), store.state.value.tracks.keys)
        assertEquals(12L, store.state.value.seq)
    }

    @Test
    fun `gap in seq asks for resync and leaves state untouched`() {
        val store = TrackStore()
        store.apply(ServerMsg.Snapshot(seq = 10, tsMs = 0, tracks = emptyList()))
        val result = store.apply(ServerMsg.TrackUpdate(12, track("b")))
        assertNotNull(result.resyncReason)
        assertTrue(store.state.value.tracks.isEmpty())
    }

    @Test
    fun `delta before snapshot asks for resync`() {
        assertNotNull(TrackStore().apply(ServerMsg.Heartbeat(1, 0, 0)).resyncReason)
    }

    @Test
    fun `heartbeat count mismatch asks for resync`() {
        val store = TrackStore()
        store.apply(ServerMsg.Snapshot(seq = 1, tsMs = 0, tracks = listOf(track("a"))))
        assertNotNull(store.apply(ServerMsg.Heartbeat(2, 0, active = 2)).resyncReason)
    }

    @Test
    fun `alerts on new track and on escalation only`() {
        val store = TrackStore()
        store.apply(ServerMsg.Snapshot(seq = 0, tsMs = 0, tracks = emptyList()))
        assertEquals(1, store.apply(ServerMsg.TrackUpdate(1, track("a"))).alerts.size)
        assertEquals(0, store.apply(ServerMsg.TrackUpdate(2, track("a"))).alerts.size)
        assertEquals(1, store.apply(ServerMsg.TrackUpdate(3, track("a", ThreatLevel.HIGH))).alerts.size)
        assertEquals(0, store.apply(ServerMsg.TrackUpdate(4, track("a", ThreatLevel.MEDIUM))).alerts.size)
    }

    @Test
    fun `resnapshot after reconnect does not re-alert known tracks`() {
        val store = TrackStore()
        store.apply(ServerMsg.Snapshot(seq = 0, tsMs = 0, tracks = listOf(track("a"))))
        store.markStale()
        val result = store.apply(ServerMsg.Snapshot(seq = 50, tsMs = 0, tracks = listOf(track("a"), track("b"))))
        assertEquals(listOf("b"), result.alerts.map { it.id })
        assertTrue(store.state.value.synced)
    }

    @Test
    fun `sorted puts most dangerous and closest first`() {
        val store = TrackStore()
        store.apply(
            ServerMsg.Snapshot(
                seq = 0, tsMs = 0,
                tracks = listOf(
                    track("far-low", ThreatLevel.LOW, 2000f),
                    track("high", ThreatLevel.HIGH, 200f),
                    track("near-low", ThreatLevel.LOW, 1100f),
                ),
            ),
        )
        assertEquals(listOf("high", "near-low", "far-low"), store.state.value.sorted.map { it.id })
    }

    @Test
    fun `decodes the rust wire format and tolerates the unknown`() {
        val json = """{"type":"track_update","seq":3,"track":{"id":"trk-0001","first_seen_ms":1,
            "last_seen_ms":2,"protocol":"ExpressLRS 2.4","kind":"controller","threat":"extreme",
            "freq_mhz":2440.0,"rssi_dbm":-70.0,"bearing_deg":12.5,"distance_m":null,"remote_id":null,
            "new_field_from_future_firmware":true}}"""
        val msg = Protocol.decode(json) as ServerMsg.TrackUpdate
        assertEquals("trk-0001", msg.track.id)
        assertEquals(ThreatLevel.UNKNOWN, msg.track.threat)
        assertNull(Protocol.decode("""{"type":"firmware_update_available","seq":4}"""))
    }
}
