//! The hub owns detector state and fans events out to every connected client.
//!
//! Consistency guarantee: `subscribe()` takes the snapshot and the broadcast
//! receiver under the same lock that `tick()` holds while it publishes. So a new
//! client sees every event with `seq > snapshot.seq`, and none from before it.
//! No client-side filtering or "replay since seq N" logic needed.

use std::sync::{Arc, Mutex};

use tokio::sync::broadcast;

use crate::protocol::{DetectorInfo, ServerMsg};
use crate::sim::{Event, Simulator};

/// A message serialised once and shared by all clients.
pub type Frame = Arc<str>;

pub struct Hub {
    info: DetectorInfo,
    state: Mutex<State>,
    tx: broadcast::Sender<Frame>,
}

struct State {
    sim: Simulator,
    seq: u64,
}

impl Hub {
    pub fn new(info: DetectorInfo, sim: Simulator) -> Arc<Self> {
        // Capacity bounds memory per slow client; a lagging client gets a fresh snapshot.
        let (tx, _) = broadcast::channel(256);
        Arc::new(Self { info, state: Mutex::new(State { sim, seq: 0 }), tx })
    }

    pub fn info(&self) -> &DetectorInfo {
        &self.info
    }

    pub fn hello(&self) -> Frame {
        encode(&ServerMsg::Hello { protocol: crate::protocol::PROTOCOL_VERSION, detector: self.info.clone() })
    }

    /// Returns the current snapshot frame plus a receiver for everything after it.
    pub fn subscribe(&self, now_ms: u64) -> (Frame, broadcast::Receiver<Frame>) {
        let state = self.state.lock().unwrap();
        let rx = self.tx.subscribe();
        let snapshot = ServerMsg::Snapshot { seq: state.seq, ts_ms: now_ms, tracks: state.sim.active_tracks() };
        (encode(&snapshot), rx)
    }

    /// Advance the simulation and publish resulting deltas.
    pub fn tick(&self, now_ms: u64) {
        let mut state = self.state.lock().unwrap();
        let events = state.sim.step(now_ms);
        for event in events {
            let msg = match event {
                Event::Updated(track) => ServerMsg::TrackUpdate { seq: 0, track },
                Event::Lost { id } => ServerMsg::TrackLost { seq: 0, ts_ms: now_ms, id },
            };
            self.publish(&mut state, msg);
        }
    }

    pub fn heartbeat(&self, now_ms: u64) {
        let mut state = self.state.lock().unwrap();
        let active = state.sim.active_tracks().len();
        self.publish(&mut state, ServerMsg::Heartbeat { seq: 0, ts_ms: now_ms, active });
    }

    /// Demo helper: make a drone appear right now.
    pub fn spawn(&self, now_ms: u64) -> String {
        let mut state = self.state.lock().unwrap();
        let track = state.sim.spawn(now_ms);
        let id = track.id.clone();
        self.publish(&mut state, ServerMsg::TrackUpdate { seq: 0, track });
        id
    }

    pub fn client_count(&self) -> usize {
        self.tx.receiver_count()
    }

    /// Assigns the next sequence number and broadcasts. Caller holds the lock.
    fn publish(&self, state: &mut State, mut msg: ServerMsg) {
        state.seq += 1;
        let seq = state.seq;
        match &mut msg {
            ServerMsg::TrackUpdate { seq: s, .. }
            | ServerMsg::TrackLost { seq: s, .. }
            | ServerMsg::Heartbeat { seq: s, .. }
            | ServerMsg::Snapshot { seq: s, .. } => *s = seq,
            ServerMsg::Hello { .. } => unreachable!("hello is per-connection"),
        }
        // Err only means "no subscribers right now", which is fine.
        let _ = self.tx.send(encode(&msg));
    }
}

fn encode(msg: &ServerMsg) -> Frame {
    serde_json::to_string(msg).expect("protocol types always serialise").into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sim::SimConfig;

    fn hub() -> Arc<Hub> {
        let info = DetectorInfo { id: "t".into(), name: "t".into(), firmware: "t".into(), position: None };
        Hub::new(info, Simulator::new(SimConfig { spawn_chance: 0.0, ..SimConfig::default() }, 1))
    }

    fn parse(f: &Frame) -> ServerMsg {
        serde_json::from_str(f).unwrap()
    }

    #[tokio::test]
    async fn subscriber_gets_contiguous_seq_after_snapshot() {
        let hub = hub();
        hub.spawn(0);
        hub.tick(500);

        let (snap, mut rx) = hub.subscribe(500);
        let ServerMsg::Snapshot { seq: snap_seq, tracks, .. } = parse(&snap) else { panic!() };
        assert_eq!(tracks.len(), 1);

        hub.tick(1_000);
        hub.heartbeat(1_000);
        let mut expected = snap_seq + 1;
        while let Ok(frame) = rx.try_recv() {
            assert_eq!(parse(&frame).seq(), Some(expected));
            expected += 1;
        }
        assert_eq!(expected, snap_seq + 3, "one track update + one heartbeat");
    }
}
