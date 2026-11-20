use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMsg {
    Hello { protocol: u32, detector: DetectorInfo },
    Snapshot { seq: u64, ts_ms: u64, tracks: Vec<Track> },
    TrackUpdate { seq: u64, track: Track },
    TrackLost { seq: u64, ts_ms: u64, id: String },
    Heartbeat { seq: u64, ts_ms: u64, active: usize },
}

impl ServerMsg {
    pub fn seq(&self) -> Option<u64> {
        match self {
            ServerMsg::Hello { .. } => None,
            ServerMsg::Snapshot { seq, .. }
            | ServerMsg::TrackUpdate { seq, .. }
            | ServerMsg::TrackLost { seq, .. }
            | ServerMsg::Heartbeat { seq, .. } => Some(*seq),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DetectorInfo {
    pub id: String,
    pub name: String,
    pub firmware: String,
    pub position: Option<GeoPoint>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Track {
    pub id: String,
    pub first_seen_ms: u64,
    pub last_seen_ms: u64,
    pub protocol: String,
    pub kind: EmitterKind,
    pub threat: ThreatLevel,
    pub freq_mhz: f32,
    pub rssi_dbm: f32,
    pub bearing_deg: Option<f32>,
    pub distance_m: Option<f32>,
    pub remote_id: Option<RemoteId>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EmitterKind {
    Drone,
    Controller,
    VideoLink,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ThreatLevel {
    Low,
    Medium,
    High,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RemoteId {
    pub serial: String,
    pub drone: GeoPoint,
    pub operator: Option<GeoPoint>,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct GeoPoint {
    pub lat: f64,
    pub lon: f64,
    pub alt_m: Option<f32>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_are_tagged_snake_case() {
        let msg = ServerMsg::TrackLost { seq: 7, ts_ms: 1, id: "trk-1".into() };
        let json = serde_json::to_value(&msg).unwrap();
        assert_eq!(json["type"], "track_lost");
        assert_eq!(json["seq"], 7);
    }

    #[test]
    fn round_trip() {
        let msg = ServerMsg::Heartbeat { seq: 3, ts_ms: 99, active: 2 };
        let text = serde_json::to_string(&msg).unwrap();
        assert_eq!(serde_json::from_str::<ServerMsg>(&text).unwrap(), msg);
    }

    #[test]
    fn seq_is_exposed_for_every_stateful_message() {
        let hello = ServerMsg::Hello {
            protocol: PROTOCOL_VERSION,
            detector: DetectorInfo {
                id: "x".into(),
                name: "x".into(),
                firmware: "x".into(),
                position: None,
            },
        };
        assert_eq!(hello.seq(), None);
        assert_eq!(ServerMsg::Heartbeat { seq: 5, ts_ms: 0, active: 0 }.seq(), Some(5));
    }
}
