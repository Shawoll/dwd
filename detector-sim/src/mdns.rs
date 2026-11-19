//! mDNS / DNS-SD advertisement, so phones find the detector without typing an IP.
//!
//! Service: `_cuas-detector._tcp.local.`
//! TXT records tell the client where things live, so paths can change
//! on the device without an app update:
//!   id=<serial>  proto=<protocol version>  ws=/ws  ui=/

use anyhow::Context;
use mdns_sd::{ServiceDaemon, ServiceInfo};

pub const SERVICE_TYPE: &str = "_cuas-detector._tcp.local.";

/// Keeps the advertisement alive. Dropping it sends a goodbye packet,
/// so phones remove the detector immediately instead of waiting for the TTL.
pub struct Advertisement {
    daemon: ServiceDaemon,
    fullname: String,
}

pub fn advertise(instance: &str, id: &str, port: u16) -> anyhow::Result<Advertisement> {
    let daemon = ServiceDaemon::new().context("starting mDNS daemon")?;
    let host = format!("{}.local.", id.to_lowercase());
    let proto = crate::protocol::PROTOCOL_VERSION.to_string();
    let props = [("id", id), ("proto", proto.as_str()), ("ws", "/ws"), ("ui", "/")];

    let info = ServiceInfo::new(SERVICE_TYPE, instance, &host, "", port, &props[..])
        .context("building mDNS service info")?
        // Advertise on every interface's addresses and follow changes (Wi-Fi up/down).
        .enable_addr_auto();
    let fullname = info.get_fullname().to_string();
    daemon.register(info).context("registering mDNS service")?;
    tracing::info!(%fullname, "advertising over mDNS");
    Ok(Advertisement { daemon, fullname })
}

impl Drop for Advertisement {
    fn drop(&mut self) {
        if let Ok(rx) = self.daemon.unregister(&self.fullname) {
            let _ = rx.recv_timeout(std::time::Duration::from_secs(1));
        }
        let _ = self.daemon.shutdown();
    }
}
