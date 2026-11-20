# Running the demo

## Simulator

```bash
cd frontend && pnpm install && pnpm build
cd ../detector-sim && cargo run --release
```

Open http://127.0.0.1:8080 and press "Simulate drone".

Flags: `--port`, `--name`, `--id`, `--seed`, `--tick-ms`, `--frontend`, `--no-mdns`.
`curl -X POST localhost:8080/api/spawn` adds a drone immediately.

## Phone

| where | how it finds the simulator |
|---|---|
| real phone on the same Wi-Fi | mDNS, "Detector SIM-01" shows up in the list |
| emulator | multicast doesn't get through NAT; enter `10.0.2.2:8080` |

## Android Auto (DHU)

1. SDK Manager: install "Android Auto Desktop Head Unit Emulator".
2. On the phone: Android Auto settings, tap Version 10x, then menu > Developer settings > enable Unknown sources.
3. Menu > Start head unit server.
4. `adb forward tcp:5277 tcp:5277`
5. Run `$ANDROID_SDK/extras/google/auto/desktop-head-unit`.

## Walkthrough

1. Start the sim and open the app. The detector appears by name. Stop the sim and it disappears
   (mDNS goodbye).
2. Connect. Status goes Connecting -> Live, tracks are sorted by threat, then distance.
3. Lock the phone and spawn a drone from the browser: heads-up alert on the lock screen.
4. Stop the sim for ~10 s and restart. The app shows Reconnecting, keeps the last tracks marked
   stale, then resyncs without re-alerting.
5. In the DHU: live list, tap a row for details. A new drone shows a heads-up there too.
6. "Open detector UI" shows the Svelte radar in a WebView.
