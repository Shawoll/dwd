use std::time::Duration;

use detector_sim::hub::Hub;
use detector_sim::protocol::{DetectorInfo, ServerMsg, PROTOCOL_VERSION};
use detector_sim::server;
use detector_sim::sim::{SimConfig, Simulator};
use futures_util::StreamExt;
use tokio_tungstenite::tungstenite::Message;

async fn next_msg<S>(ws: &mut S) -> ServerMsg
where
    S: StreamExt<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    loop {
        let frame = tokio::time::timeout(Duration::from_secs(2), ws.next())
            .await
            .expect("timed out waiting for frame")
            .expect("stream ended")
            .expect("ws error");
        if let Message::Text(text) = frame {
            return serde_json::from_str(&text).expect("valid protocol message");
        }
    }
}

#[tokio::test]
async fn hello_snapshot_then_ordered_deltas() {
    let info = DetectorInfo { id: "T1".into(), name: "Test".into(), firmware: "t".into(), position: None };
    let sim = Simulator::new(SimConfig { spawn_chance: 0.0, ..SimConfig::default() }, 1);
    let hub = Hub::new(info, sim);
    hub.spawn(0);

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(axum::serve(listener, server::router(hub.clone(), None)).into_future());

    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{addr}/ws")).await.unwrap();

    match next_msg(&mut ws).await {
        ServerMsg::Hello { protocol, detector } => {
            assert_eq!(protocol, PROTOCOL_VERSION);
            assert_eq!(detector.id, "T1");
        }
        other => panic!("expected hello, got {other:?}"),
    }
    let snap_seq = match next_msg(&mut ws).await {
        ServerMsg::Snapshot { seq, tracks, .. } => {
            assert_eq!(tracks.len(), 1, "snapshot includes tracks from before connect");
            seq
        }
        other => panic!("expected snapshot, got {other:?}"),
    };

    let new_id = hub.spawn(1_000);
    hub.heartbeat(1_000);

    match next_msg(&mut ws).await {
        ServerMsg::TrackUpdate { seq, track } => {
            assert_eq!(seq, snap_seq + 1);
            assert_eq!(track.id, new_id);
        }
        other => panic!("expected track_update, got {other:?}"),
    }
    match next_msg(&mut ws).await {
        ServerMsg::Heartbeat { seq, active, .. } => {
            assert_eq!(seq, snap_seq + 2);
            assert_eq!(active, 2);
        }
        other => panic!("expected heartbeat, got {other:?}"),
    }
}

#[tokio::test]
async fn info_endpoint_reports_detector() {
    let info = DetectorInfo { id: "T2".into(), name: "Test".into(), firmware: "t".into(), position: None };
    let hub = Hub::new(info, Simulator::new(SimConfig::default(), 1));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(axum::serve(listener, server::router(hub, None)).into_future());

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut tcp = tokio::net::TcpStream::connect(addr).await.unwrap();
    tcp.write_all(b"GET /api/info HTTP/1.0\r\n\r\n").await.unwrap();
    let mut body = String::new();
    tcp.read_to_string(&mut body).await.unwrap();
    assert!(body.starts_with("HTTP/1.0 200") || body.starts_with("HTTP/1.1 200"), "{body}");
    assert!(body.contains("\"id\":\"T2\""));
}
