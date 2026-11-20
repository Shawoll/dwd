# Protocol (v1)

Server to client only, JSON text frames tagged by `type`. Defined in
`detector-sim/src/protocol.rs`, mirrored in `android/.../model/Protocol.kt`.

| type | when | payload |
|---|---|---|
| `hello` | first frame | `protocol`, `detector {id, name, firmware, position}` |
| `snapshot` | second frame, and again if the client lagged | `seq`, `ts_ms`, `tracks[]` |
| `track_update` | track new or changed | `seq`, `track` (upsert by id) |
| `track_lost` | track timed out | `seq`, `ts_ms`, `id` |
| `heartbeat` | every 2 s | `seq`, `ts_ms`, `active` |

## Sequence numbers

Every message except `hello` has a global `seq`. The hub takes the snapshot and subscribes the
client under the same lock it publishes with, so after a snapshot with `seq = N` the client gets
`N+1, N+2, ...` with no gaps.

If the client sees a gap, or a heartbeat whose `active` doesn't match its own track count, it
reconnects. The server always starts with a fresh snapshot, so there's no replay logic.

## Compatibility

Clients ignore unknown fields and message types; unknown enum values map to `UNKNOWN`.
`protocol` is bumped only for breaking changes. The app skips detectors with a different
major version at discovery time.

## mDNS

Service `_cuas-detector._tcp`, TXT records `id=<serial> proto=1 ws=/ws ui=/`.
The paths come from the device, so firmware can change them without an app update.
