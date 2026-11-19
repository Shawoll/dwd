<script>
  // Polar plot: detector at the centre, north up, range on a sqrt scale so
  // close (dangerous) contacts get more room than far ones.
  let { tracks, selected = $bindable(null) } = $props();

  const MAX_M = 3000;
  const R = 140;
  const rings = [250, 1000, 2000, 3000];
  const radius = (m) => Math.sqrt(Math.min(m, MAX_M) / MAX_M) * R;
  const polar = (bearing, m) => {
    const a = ((bearing - 90) * Math.PI) / 180;
    const r = radius(m);
    return [Math.cos(a) * r, Math.sin(a) * r];
  };
  const label = (m) => (m >= 1000 ? `${m / 1000} km` : `${m} m`);
</script>

<svg viewBox="-160 -160 320 320" role="img" aria-label="Detections by bearing and range">
  {#each rings as m}
    <circle r={radius(m)} class="ring" />
    <text x={radius(m) - 3} y="-3" class="ring-label">{label(m)}</text>
  {/each}
  {#each [0, 45, 90, 135] as deg}
    {@const [x, y] = polar(deg, MAX_M)}
    <line x1={-x} y1={-y} x2={x} y2={y} class="spoke" />
  {/each}
  <text y={-R - 6} class="north">N</text>

  <g class="sweep"><path d="M0 0 L0 {-R} A{R} {R} 0 0 1 {R * Math.sin(0.5)} {-R * Math.cos(0.5)} Z" /></g>
  <circle r="3" class="self" />

  {#each tracks as t (t.id)}
    {#if t.bearing_deg != null && t.distance_m != null}
      {@const [x, y] = polar(t.bearing_deg, t.distance_m)}
      <g
        class="contact {t.threat}"
        class:selected={selected === t.id}
        transform="translate({x} {y})"
        onclick={() => (selected = t.id)}
        role="button"
        tabindex="0"
        onkeydown={(e) => e.key === 'Enter' && (selected = t.id)}
      >
        {#if t.threat === 'high'}<circle r="9" class="pulse" />{/if}
        <circle r="4.5" class="blip" />
        <text x="8" y="3" class="tag">{t.id.replace('trk-', '#')}</text>
      </g>
    {/if}
  {/each}
</svg>

<style>
  svg { width: 100%; height: auto; display: block; max-height: 70vh; }
  .ring { fill: none; stroke: var(--grid); stroke-width: 1; }
  .spoke { stroke: var(--grid); stroke-width: 0.6; }
  .ring-label { fill: var(--muted); font: 8px var(--mono); text-anchor: end; }
  .north { fill: var(--muted); font: 600 10px var(--sans); text-anchor: middle; }
  .self { fill: var(--accent); }

  .sweep { animation: spin 4s linear infinite; transform-origin: 0 0; }
  .sweep path { fill: color-mix(in srgb, var(--accent) 9%, transparent); }
  @keyframes spin { to { transform: rotate(360deg); } }
  @media (prefers-reduced-motion: reduce) { .sweep { display: none; } }

  .contact { cursor: pointer; transition: transform 0.45s linear; }
  .contact .blip { stroke: var(--bg); stroke-width: 1.5; }
  .contact.low .blip { fill: var(--low); }
  .contact.medium .blip { fill: var(--medium); }
  .contact.high .blip { fill: var(--high); }
  .contact.selected .blip { stroke: var(--text); }
  .tag { fill: var(--text); font: 8px var(--mono); }
  .pulse { fill: none; stroke: var(--high); animation: pulse 1.4s ease-out infinite; transform-origin: 0 0; }
  @keyframes pulse { from { opacity: 0.9; transform: scale(0.6); } to { opacity: 0; transform: scale(1.8); } }
</style>
