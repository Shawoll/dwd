# Design notes

## Discovery

`NsdManager` instead of jmDNS: the system daemon handles multicast, so no `MulticastLock`,
no port 5353 conflicts, and it works in the background. Things to watch for:

- callbacks come on binder threads, so they go through one channel and are handled in order
- before API 34 only one `resolveService` can run at a time, so resolves are serialised
- resolves can hang, so there's a 5 s timeout
- IPv4 is preferred; link-local IPv6 needs scope IDs that OkHttp and WebView handle badly

`resolveService` is deprecated on API 34. It still works down to minSdk 26; on 34+ it should
switch to `registerServiceInfoCallback`.

## Wi-Fi without internet

In the field the detector is probably its own access point with no internet. Android then keeps
mobile data as the default network and a socket to `192.168.x.x` goes over LTE. `WifiBinding`
requests the Wi-Fi network without `NET_CAPABILITY_INTERNET` and binds the process to it.
That also covers the WebView. The app has no other traffic, so binding the whole process is fine.

## Background

- Foreground service of type `connectedDevice`. `dataSync` would hit the 6 h daily limit on Android 15.
- Partial wake lock, only while connected. In a car the phone is usually charging.
- Wi-Fi lock for latency.
- OkHttp pings every 10 s; a watchdog closes the socket after 6 s without frames.
- Exponential backoff with jitter. If the link is fine but out of sync, reconnect immediately.

## Alerts

Only for new tracks or a threat escalation, and not for tracks already known before a reconnect.
One notification per track id, so an escalation replaces the old one. `CarAppExtender` shows it
on the car screen too.

## Android Auto

- Category `IOT`, which needs Car API level 6.
- The list title is constant so updates count as refreshes, not new steps. Whether a changing
  row count also counts as a refresh depends on the host. Needs checking on real head units;
  fallback would be fixed-size slots.
- Row count comes from `ConstraintManager`; redraws are throttled to once a second.
- When the link drops, the last tracks stay visible and are marked stale.

## WebView

Navigation is limited to the detector's origin, other links open in the browser. No JS bridge,
so the UI stays the same build a laptop browser gets. Paused with the activity, native error
screen, dark background.

## Cleartext

The device serves plain HTTP by IP and `domain-config` can't match IP ranges, so cleartext is
allowed app-wide. For production: TLS with a per-device cert pinned at pairing, plus a token on `/ws`.

## Rust

Frames are serialised once and shared between clients. A client that lags the broadcast channel
gets a new snapshot instead of a growing backlog.
