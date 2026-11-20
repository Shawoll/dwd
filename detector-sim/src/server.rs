use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::{Json, Router};
use tokio::sync::broadcast::error::RecvError;
use tower_http::services::{ServeDir, ServeFile};

use crate::hub::Hub;

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

pub fn router(hub: Arc<Hub>, frontend: Option<PathBuf>) -> Router {
    let api = Router::new()
        .route("/ws", get(ws_upgrade))
        .route("/api/info", get(info))
        .route("/api/spawn", post(spawn))
        .with_state(hub);

    match frontend {
        Some(dir) => {
            let index = ServeFile::new(dir.join("index.html"));
            api.fallback_service(ServeDir::new(dir).fallback(index))
        }
        None => api,
    }
}

async fn info(State(hub): State<Arc<Hub>>) -> impl IntoResponse {
    Json(serde_json::json!({
        "detector": hub.info(),
        "protocol": crate::protocol::PROTOCOL_VERSION,
        "clients": hub.client_count(),
    }))
}

async fn spawn(State(hub): State<Arc<Hub>>) -> impl IntoResponse {
    Json(serde_json::json!({ "id": hub.spawn(now_ms()) }))
}

async fn ws_upgrade(ws: WebSocketUpgrade, State(hub): State<Arc<Hub>>) -> impl IntoResponse {
    ws.on_upgrade(move |socket| client(socket, hub))
}

async fn client(mut socket: WebSocket, hub: Arc<Hub>) {
    let (snapshot, mut rx) = hub.subscribe(now_ms());
    tracing::info!(clients = hub.client_count(), "client connected");

    for frame in [hub.hello(), snapshot] {
        if send(&mut socket, frame).await.is_err() {
            return;
        }
    }

    loop {
        tokio::select! {
            event = rx.recv() => match event {
                Ok(frame) => {
                    if send(&mut socket, frame).await.is_err() { break; }
                }
                // Slow client: send a fresh snapshot instead of a backlog.
                Err(RecvError::Lagged(skipped)) => {
                    tracing::warn!(skipped, "client lagged, resyncing with snapshot");
                    let (snapshot, fresh) = hub.subscribe(now_ms());
                    rx = fresh;
                    if send(&mut socket, snapshot).await.is_err() { break; }
                }
                Err(RecvError::Closed) => break,
            },
            incoming = socket.recv() => match incoming {
                Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                Some(Ok(_)) => {}
            },
        }
    }
    tracing::info!("client disconnected");
}

async fn send(socket: &mut WebSocket, frame: crate::hub::Frame) -> Result<(), axum::Error> {
    socket.send(Message::Text(frame.as_ref().into())).await
}
