use std::path::PathBuf;
use std::time::Duration;

use clap::Parser;
use detector_sim::hub::Hub;
use detector_sim::protocol::DetectorInfo;
use detector_sim::sim::{SimConfig, Simulator};
use detector_sim::{mdns, server};

#[derive(Parser)]
#[command(about = "Simulated passive drone detector: mDNS + WebSocket + hosted UI")]
struct Args {
    #[arg(long, default_value_t = 8080)]
    port: u16,
    /// Instance name shown in the app's detector list.
    #[arg(long, default_value = "Detector SIM-01")]
    name: String,
    /// Detector serial, also used as the mDNS host name.
    #[arg(long, default_value = "SIM01")]
    id: String,
    #[arg(long, default_value_t = 7)]
    seed: u64,
    #[arg(long, default_value_t = 500)]
    tick_ms: u64,
    /// Built Svelte UI to serve at `/`.
    #[arg(long, default_value = concat!(env!("CARGO_MANIFEST_DIR"), "/../frontend/dist"))]
    frontend: PathBuf,
    /// Skip mDNS (e.g. on networks that block multicast).
    #[arg(long)]
    no_mdns: bool,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();
    let args = Args::parse();

    let cfg = SimConfig::default();
    let info = DetectorInfo {
        id: args.id.clone(),
        name: args.name.clone(),
        firmware: concat!("sim-", env!("CARGO_PKG_VERSION")).into(),
        position: Some(cfg.origin),
    };
    let hub = Hub::new(info, Simulator::new(cfg, args.seed));

    let ticker = hub.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_millis(args.tick_ms));
        let mut beat = tokio::time::interval(Duration::from_secs(2));
        loop {
            tokio::select! {
                _ = tick.tick() => ticker.tick(server::now_ms()),
                _ = beat.tick() => ticker.heartbeat(server::now_ms()),
            }
        }
    });

    let frontend = if args.frontend.join("index.html").exists() {
        Some(args.frontend.clone())
    } else {
        tracing::warn!(dir = %args.frontend.display(), "frontend not built; run `pnpm build` in frontend/");
        None
    };

    let listener = tokio::net::TcpListener::bind(("0.0.0.0", args.port)).await?;
    let _mdns = if args.no_mdns { None } else { Some(mdns::advertise(&args.name, &args.id, args.port)?) };
    tracing::info!("listening on http://0.0.0.0:{}", args.port);

    axum::serve(listener, server::router(hub, frontend))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
            tracing::info!("shutting down, sending mDNS goodbye");
        })
        .await?;
    Ok(())
}
