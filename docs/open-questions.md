# Open questions

- Does the real detector report bearing and range, or only signal and protocol? This affects the car UI most.
- Is the detector its own AP, a client on the vehicle's hotspot, or both?
- Is there an existing WebSocket protocol on the device? This one is a proposal; the client should adapt to the real one.
- Auth/TLS expectations, and whether several phones per detector is a real case.
- Distribution: Play Store (IOT category review) or sideloaded/managed? Is Android Automotive OS planned?

# Next steps

- Build and run on a device and the DHU
- `registerServiceInfoCallback` on API 34+
- Instrumented test for the reconnect path
- Fixed-slot list fallback for Android Auto
- Remote ID map view
- Pairing + TLS
- CI: cargo test, gradle test, pnpm build
