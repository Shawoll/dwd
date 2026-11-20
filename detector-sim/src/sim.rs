use std::collections::BTreeMap;

use rand::{rngs::StdRng, Rng, SeedableRng};

use crate::protocol::{EmitterKind, GeoPoint, RemoteId, ThreatLevel, Track};

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    Updated(Track),
    Lost { id: String },
}

#[derive(Debug, Clone)]
pub struct SimConfig {
    pub origin: GeoPoint,
    pub spawn_chance: f64,
    pub max_tracks: usize,
    pub lost_after_ms: u64,
}

impl Default for SimConfig {
    fn default() -> Self {
        Self {
            origin: GeoPoint { lat: 48.1486, lon: 17.1077, alt_m: Some(140.0) },
            spawn_chance: 0.08,
            max_tracks: 6,
            lost_after_ms: 6_000,
        }
    }
}

struct Profile {
    protocol: &'static str,
    kind: EmitterKind,
    freq_mhz: f32,
    remote_id: bool,
}

const PROFILES: &[Profile] = &[
    Profile { protocol: "DJI OcuSync 3", kind: EmitterKind::Drone, freq_mhz: 5_745.0, remote_id: true },
    Profile { protocol: "DJI OcuSync 2", kind: EmitterKind::Drone, freq_mhz: 2_437.0, remote_id: true },
    Profile { protocol: "ExpressLRS 2.4", kind: EmitterKind::Controller, freq_mhz: 2_440.0, remote_id: false },
    Profile { protocol: "Crossfire 868", kind: EmitterKind::Controller, freq_mhz: 868.0, remote_id: false },
    Profile { protocol: "Analog FPV video", kind: EmitterKind::VideoLink, freq_mhz: 5_800.0, remote_id: false },
    Profile { protocol: "Remote ID (Wi-Fi beacon)", kind: EmitterKind::Drone, freq_mhz: 2_412.0, remote_id: true },
];

struct SimTrack {
    track: Track,
    radial_speed: f32,
    angular_speed: f32,
    silent_at_ms: u64,
}

pub struct Simulator {
    cfg: SimConfig,
    rng: StdRng,
    tracks: BTreeMap<String, SimTrack>,
    next_id: u32,
    last_step_ms: Option<u64>,
}

impl Simulator {
    pub fn new(cfg: SimConfig, seed: u64) -> Self {
        Self { cfg, rng: StdRng::seed_from_u64(seed), tracks: BTreeMap::new(), next_id: 1, last_step_ms: None }
    }

    pub fn active_tracks(&self) -> Vec<Track> {
        self.tracks.values().map(|t| t.track.clone()).collect()
    }

    pub fn spawn(&mut self, now_ms: u64) -> Track {
        let profile = &PROFILES[self.rng.gen_range(0..PROFILES.len())];
        let id = format!("trk-{:04}", self.next_id);
        self.next_id += 1;

        let distance = self.rng.gen_range(600.0..2_500.0_f32);
        let bearing = self.rng.gen_range(0.0..360.0_f32);
        let mut track = Track {
            id: id.clone(),
            first_seen_ms: now_ms,
            last_seen_ms: now_ms,
            protocol: profile.protocol.to_string(),
            kind: profile.kind,
            threat: ThreatLevel::Low,
            freq_mhz: profile.freq_mhz + self.rng.gen_range(-5.0..5.0_f32),
            rssi_dbm: 0.0,
            bearing_deg: Some(bearing),
            distance_m: Some(distance),
            remote_id: None,
        };
        if profile.remote_id {
            track.remote_id = Some(RemoteId {
                serial: format!("1581F{:08X}", self.rng.gen_range(0..=u32::MAX)),
                drone: self.cfg.origin,
                operator: None,
            });
        }
        refresh_derived(&mut track, &self.cfg.origin);

        let lifetime = self.rng.gen_range(12_000..40_000);
        self.tracks.insert(
            id,
            SimTrack {
                track: track.clone(),
                radial_speed: self.rng.gen_range(-4.0..14.0),
                angular_speed: self.rng.gen_range(-2.0..2.0),
                silent_at_ms: now_ms + lifetime,
            },
        );
        track
    }

    pub fn step(&mut self, now_ms: u64) -> Vec<Event> {
        let dt_s = self.last_step_ms.map_or(0.0, |last| now_ms.saturating_sub(last) as f32 / 1000.0);
        self.last_step_ms = Some(now_ms);
        let mut events = Vec::new();

        let mut lost = Vec::new();
        for (id, sim) in self.tracks.iter_mut() {
            if now_ms < sim.silent_at_ms {
                let t = &mut sim.track;
                let d = (t.distance_m.unwrap_or(1_000.0) - sim.radial_speed * dt_s).clamp(40.0, 4_000.0);
                t.distance_m = Some(d);
                t.bearing_deg = t.bearing_deg.map(|b| (b + sim.angular_speed * dt_s).rem_euclid(360.0));
                t.last_seen_ms = now_ms;
                refresh_derived(t, &self.cfg.origin);
                events.push(Event::Updated(t.clone()));
            } else if now_ms - sim.track.last_seen_ms >= self.cfg.lost_after_ms {
                lost.push(id.clone());
            }
        }
        for id in lost {
            self.tracks.remove(&id);
            events.push(Event::Lost { id });
        }
        if self.tracks.len() < self.cfg.max_tracks && self.rng.gen_bool(self.cfg.spawn_chance) {
            events.push(Event::Updated(self.spawn(now_ms)));
        }
        events
    }
}

fn refresh_derived(t: &mut Track, origin: &GeoPoint) {
    let d = t.distance_m.unwrap_or(1_000.0).max(1.0);
    t.rssi_dbm = (-20.0 - 20.0 * d.log10()).round();
    t.threat = match d {
        d if d < 300.0 => ThreatLevel::High,
        d if d < 1_000.0 => ThreatLevel::Medium,
        _ => ThreatLevel::Low,
    };
    if let (Some(rid), Some(bearing)) = (t.remote_id.as_mut(), t.bearing_deg) {
        rid.drone = offset(origin, bearing, d);
        rid.drone.alt_m = Some(origin.alt_m.unwrap_or(0.0) + 80.0);
        rid.operator = Some(offset(origin, (bearing + 12.0).rem_euclid(360.0), d * 1.3));
    }
}

fn offset(origin: &GeoPoint, bearing_deg: f32, distance_m: f32) -> GeoPoint {
    const EARTH_R: f64 = 6_371_000.0;
    let b = (bearing_deg as f64).to_radians();
    let d = distance_m as f64;
    let dlat = d * b.cos() / EARTH_R;
    let dlon = d * b.sin() / (EARTH_R * origin.lat.to_radians().cos());
    GeoPoint { lat: origin.lat + dlat.to_degrees(), lon: origin.lon + dlon.to_degrees(), alt_m: None }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn quiet() -> SimConfig {
        SimConfig { spawn_chance: 0.0, ..SimConfig::default() }
    }

    #[test]
    fn same_seed_same_traffic() {
        let run = |seed| {
            let mut sim = Simulator::new(SimConfig { spawn_chance: 0.5, ..SimConfig::default() }, seed);
            (0..50).flat_map(|i| sim.step(i * 500)).collect::<Vec<_>>()
        };
        assert_eq!(run(42), run(42));
    }

    #[test]
    fn spawned_track_moves_then_is_lost() {
        let mut sim = Simulator::new(quiet(), 1);
        let t = sim.spawn(0);
        sim.step(0);

        let lost_at = (0..2_000u64)
            .map(|i| i * 500)
            .find(|&now| sim.step(now).iter().any(|e| matches!(e, Event::Lost { id } if *id == t.id)))
            .expect("track should eventually be lost");

        assert!(sim.active_tracks().is_empty());
        assert!(lost_at <= 40_000 + quiet().lost_after_ms + 500);
    }

    #[test]
    fn threat_follows_distance() {
        let mut t = Simulator::new(quiet(), 3).spawn(0);
        for (d, expected) in [(200.0, ThreatLevel::High), (600.0, ThreatLevel::Medium), (2_000.0, ThreatLevel::Low)] {
            t.distance_m = Some(d);
            refresh_derived(&mut t, &SimConfig::default().origin);
            assert_eq!(t.threat, expected, "distance {d}");
        }
    }

    #[test]
    fn closer_is_louder() {
        let mut t = Simulator::new(quiet(), 4).spawn(0);
        let origin = SimConfig::default().origin;
        t.distance_m = Some(2_000.0);
        refresh_derived(&mut t, &origin);
        let far = t.rssi_dbm;
        t.distance_m = Some(100.0);
        refresh_derived(&mut t, &origin);
        assert!(t.rssi_dbm > far);
    }

    #[test]
    fn never_exceeds_max_tracks() {
        let cfg = SimConfig { spawn_chance: 1.0, max_tracks: 3, ..SimConfig::default() };
        let mut sim = Simulator::new(cfg, 9);
        for i in 0..100 {
            sim.step(i * 500);
            assert!(sim.active_tracks().len() <= 3);
        }
    }
}
