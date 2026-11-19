// Live detection stream. Same rules as the Android client:
// snapshot replaces state, deltas must arrive with seq = last + 1,
// a gap means we missed something, so reconnect and take a fresh snapshot.

export function createStream(url = defaultUrl()) {
  const state = $state({
    status: 'connecting', // connecting | live | reconnecting
    detector: null,
    tracks: {},
    seq: 0,
    lastHeartbeat: 0,
  });

  let ws;
  let attempt = 0;
  let timer;

  function connect() {
    ws = new WebSocket(url);
    ws.onmessage = (e) => handle(JSON.parse(e.data));
    ws.onclose = () => {
      state.status = 'reconnecting';
      // Exponential backoff with jitter, capped at 10 s.
      const delay = Math.min(10_000, 500 * 2 ** attempt++) * (0.5 + Math.random() / 2);
      timer = setTimeout(connect, delay);
    };
  }

  function resync(reason) {
    console.warn('resync:', reason);
    ws.close(); // onclose reconnects; server opens with hello + snapshot
  }

  function handle(msg) {
    switch (msg.type) {
      case 'hello':
        state.detector = msg.detector;
        return;
      case 'snapshot':
        state.tracks = Object.fromEntries(msg.tracks.map((t) => [t.id, t]));
        state.seq = msg.seq;
        state.status = 'live';
        attempt = 0;
        return;
    }
    if (msg.seq !== undefined && msg.seq !== state.seq + 1) {
      return resync(`seq gap ${state.seq} -> ${msg.seq}`);
    }
    state.seq = msg.seq;
    switch (msg.type) {
      case 'track_update':
        state.tracks[msg.track.id] = msg.track;
        break;
      case 'track_lost':
        delete state.tracks[msg.id];
        break;
      case 'heartbeat':
        state.lastHeartbeat = msg.ts_ms;
        if (msg.active !== Object.keys(state.tracks).length) resync('count mismatch');
        break;
      // Unknown types are ignored on purpose: newer firmware must not break older UIs.
    }
  }

  connect();
  return {
    state,
    close() {
      clearTimeout(timer);
      ws.onclose = null;
      ws.close();
    },
  };
}

function defaultUrl() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${location.host}/ws`;
}
