//! Detector simulator: stands in for the real detector during app development.
//! Same discovery (mDNS), same stream (WebSocket JSON), same hosted UI.

pub mod hub;
pub mod mdns;
pub mod protocol;
pub mod server;
pub mod sim;
