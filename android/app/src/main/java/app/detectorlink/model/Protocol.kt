package app.detectorlink.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface ServerMsg {
    @Serializable
    @SerialName("hello")
    data class Hello(val protocol: Int, val detector: DetectorInfo) : ServerMsg

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val seq: Long, @SerialName("ts_ms") val tsMs: Long, val tracks: List<Track>) : ServerMsg

    @Serializable
    @SerialName("track_update")
    data class TrackUpdate(val seq: Long, val track: Track) : ServerMsg

    @Serializable
    @SerialName("track_lost")
    data class TrackLost(val seq: Long, @SerialName("ts_ms") val tsMs: Long, val id: String) : ServerMsg

    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(val seq: Long, @SerialName("ts_ms") val tsMs: Long, val active: Int) : ServerMsg
}

@Serializable
data class DetectorInfo(
    val id: String,
    val name: String,
    val firmware: String,
    val position: GeoPoint? = null,
)

@Serializable
data class Track(
    val id: String,
    @SerialName("first_seen_ms") val firstSeenMs: Long,
    @SerialName("last_seen_ms") val lastSeenMs: Long,
    val protocol: String,
    val kind: EmitterKind = EmitterKind.UNKNOWN,
    val threat: ThreatLevel = ThreatLevel.UNKNOWN,
    @SerialName("freq_mhz") val freqMhz: Float,
    @SerialName("rssi_dbm") val rssiDbm: Float,
    @SerialName("bearing_deg") val bearingDeg: Float? = null,
    @SerialName("distance_m") val distanceM: Float? = null,
    @SerialName("remote_id") val remoteId: RemoteId? = null,
)

@Serializable
enum class EmitterKind {
    @SerialName("drone") DRONE,
    @SerialName("controller") CONTROLLER,
    @SerialName("video_link") VIDEO_LINK,
    UNKNOWN,
}

@Serializable
enum class ThreatLevel {
    UNKNOWN,
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
}

@Serializable
data class RemoteId(val serial: String, val drone: GeoPoint, val operator: GeoPoint? = null)

@Serializable
data class GeoPoint(val lat: Double, val lon: Double, @SerialName("alt_m") val altM: Float? = null)

object Protocol {
    const val VERSION = 1

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true // unknown enum value -> UNKNOWN
        explicitNulls = false
    }

    fun decode(text: String): ServerMsg? =
        try {
            json.decodeFromString(ServerMsg.serializer(), text)
        } catch (e: IllegalArgumentException) {
            null
        }
}
