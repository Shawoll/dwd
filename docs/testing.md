# Testing

```bash
cd detector-sim && cargo test && cargo clippy --all-targets
```

Covers protocol serialisation, simulator determinism and track lifecycle, `seq` continuity in
the hub, and an end-to-end WebSocket test against the real router.

On Android, `TrackStoreTest` covers snapshot/delta handling, gap detection, alerting rules,
sorting and unknown fields. It was run on the JVM against the same source files, together with a
replay of 40 frames captured from the running simulator.

The rest of the Android module (service, NSD, Car App, Compose) hasn't been compiled yet.
First step on a machine with the SDK: build in Android Studio and run on a device.
