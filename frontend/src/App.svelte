<script>
  import { onDestroy } from 'svelte';
  import { createStream } from './stream.svelte.js';
  import Radar from './Radar.svelte';

  const stream = createStream();
  const s = stream.state;
  onDestroy(() => stream.close());

  const rank = { high: 0, medium: 1, low: 2 };
  const sorted = $derived(
    Object.values(s.tracks).sort(
      (a, b) => rank[a.threat] - rank[b.threat] || (a.distance_m ?? 1e9) - (b.distance_m ?? 1e9),
    ),
  );
  let selected = $state(null);

  async function simulate() {
    await fetch('/api/spawn', { method: 'POST' });
  }

  const fmtDist = (m) => (m == null ? '—' : m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`);
</script>

<header>
  <div class="id">
    <span class="dot {s.status}"></span>
    <strong>{s.detector?.name ?? 'Detector'}</strong>
    <span class="muted mono">{s.detector?.firmware ?? ''}</span>
  </div>
  <div class="meta mono">
    <span>{s.status}</span>
    <span class="muted">seq {s.seq}</span>
  </div>
</header>

<main>
  <section class="radar">
    <Radar tracks={sorted} bind:selected />
  </section>

  <section class="list">
    <div class="list-head">
      <h2>Active tracks <span class="count mono">{sorted.length}</span></h2>
      <button class="ghost" onclick={simulate}>Simulate drone</button>
    </div>

    {#if sorted.length === 0}
      <p class="empty">No emitters detected. Spectrum is quiet.</p>
    {:else}
      <ul>
        {#each sorted as t (t.id)}
          <li>
            <button class="row" class:active={selected === t.id} onclick={() => (selected = t.id)}>
              <span class="bar {t.threat}"></span>
              <span class="main">
                <span class="proto">{t.protocol}</span>
                <span class="sub mono">
                  {t.bearing_deg != null ? `${Math.round(t.bearing_deg)}°` : '—'} · {fmtDist(t.distance_m)} ·
                  {t.rssi_dbm} dBm · {t.freq_mhz.toFixed(0)} MHz
                </span>
                {#if t.remote_id}
                  <span class="sub mono rid">RID {t.remote_id.serial}</span>
                {/if}
              </span>
              <span class="threat {t.threat}">{t.threat}</span>
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  </section>
</main>

<style>
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    padding-top: max(12px, env(safe-area-inset-top));
    border-bottom: 1px solid var(--line);
    background: var(--panel);
  }
  .id { display: flex; align-items: center; gap: 10px; min-width: 0; }
  .id strong { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .meta { display: flex; gap: 12px; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
  .muted { color: var(--muted); }
  .mono { font-family: var(--mono); }

  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--medium); flex: none; }
  .dot.live { background: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 25%, transparent); }
  .dot.reconnecting { background: var(--high); }

  main {
    display: grid;
    grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
    gap: 16px;
    padding: 16px;
    max-width: 1200px;
    margin: 0 auto;
  }
  @media (max-width: 760px) {
    main { grid-template-columns: minmax(0, 1fr); }
  }

  .radar, .list { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; }
  .radar { padding: 12px; }
  .list { padding: 4px 0; }

  .list-head { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; }
  h2 { font-size: 14px; font-weight: 600; margin: 0; display: flex; align-items: center; gap: 8px; }
  .count { color: var(--muted); font-weight: 400; }

  .ghost {
    background: transparent;
    border: 1px solid var(--line);
    border-radius: 6px;
    padding: 6px 10px;
    font-size: 13px;
    cursor: pointer;
  }
  .ghost:hover { border-color: var(--accent); color: var(--accent); }

  .empty { color: var(--muted); padding: 8px 14px 18px; margin: 0; font-size: 14px; }

  ul { list-style: none; margin: 0; padding: 0; }
  .row {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 14px;
    background: none;
    border: 0;
    border-top: 1px solid var(--line);
    text-align: left;
    cursor: pointer;
  }
  .row.active, .row:hover { background: color-mix(in srgb, var(--accent) 6%, transparent); }
  .bar { width: 3px; align-self: stretch; border-radius: 2px; flex: none; }
  .bar.low { background: var(--low); }
  .bar.medium { background: var(--medium); }
  .bar.high { background: var(--high); }
  .main { display: flex; flex-direction: column; gap: 3px; min-width: 0; flex: 1; }
  .proto { font-weight: 600; font-size: 14px; }
  .sub { font-size: 12px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .rid { color: color-mix(in srgb, var(--accent) 70%, var(--muted)); }
  .threat { font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; font-weight: 600; }
  .threat.low { color: var(--low); }
  .threat.medium { color: var(--medium); }
  .threat.high { color: var(--high); }
</style>
