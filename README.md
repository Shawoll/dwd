# Detector Link

Proof of concept for an Android app that finds a drone detector on the local network (mDNS),
streams its detections over WebSocket (also in the background), shows them on Android Auto,
and embeds the detector's web UI.

There's no real detector here, so `detector-sim/` is a small Rust server that pretends to be one.

```
detector-sim/   Rust (axum, tokio, mdns-sd) - simulated detector
frontend/       Svelte 5 + Vite - the detector's web UI
android/        Kotlin, Compose, OkHttp, Car App Library
```

## Running it

```bash
cd frontend && pnpm install && pnpm build
cd ../detector-sim && cargo run --release   # http://127.0.0.1:8080
```

Then open `android/` in Android Studio and run it on a phone on the same Wi-Fi.
On the emulator mDNS doesn't work; enter `10.0.2.2:8080` manually.

Note: the Android module hasn't been compiled yet (no SDK on the machine it was written on).

## Docs

- [Architecture](docs/architecture.md)
- [Protocol](docs/protocol.md)
- [Design notes](docs/design-notes.md)
- [Running the demo](docs/demo.md), incl. Android Auto DHU setup
- [Testing](docs/testing.md)
- [Open questions and next steps](docs/open-questions.md)
