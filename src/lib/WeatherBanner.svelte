<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import type { WeatherReport } from '$lib/weather';
  let { report, showRain = false, rainOnly = false, busy = $bindable(false) }: { report: WeatherReport; showRain?: boolean; rainOnly?: boolean; busy?: boolean } = $props();
  let message = $state('');
  let requestId: string | undefined;
  $effect(() => {
    if (!report.locationSet || rainOnly) return;
    const timer = setInterval(() => { if (!busy) void invalidateAll(); }, report.forecastFetchedAt ? 30000 : 3000);
    return () => clearInterval(timer);
  });
  async function water() {
    busy = true; message = '';
    requestId ??= crypto.randomUUID();
    try {
      const response = await fetch('/api/weather/rain/water', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ request_id: requestId }) });
      const result = await response.json();
      if (!response.ok) { message = response.status === 409 && result.message === 'No recent rain report for outdoor plants.' ? 'Rain watering is not ready. Try again in a moment.' : result.message ?? 'Could not mark outdoor plants watered.'; return; }
      requestId = undefined;
      message = `Marked ${result.plantCount} outdoor plants watered.`;
      await invalidateAll();
    } catch { message = 'Could not reach Fytorium. Retry uses the same request.'; }
    finally { busy = false; }
  }
  async function undo() {
    if (!report.lastRainBatch) return;
    busy = true; message = '';
    try {
      const response = await fetch('/api/task-batches/' + report.lastRainBatch.id + '/undo', { method: 'POST' });
      if (!response.ok) { message = (await response.json()).message ?? 'Could not undo rain watering.'; return; }
      message = 'Rain watering undone.';
      await invalidateAll();
    } catch { message = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
{#if report.locationSet || report.lastRainBatch || rainOnly}
<section aria-labelledby={rainOnly ? undefined : "weather-title"}>
  {#if !rainOnly}
  <h2 id="weather-title">Weather</h2>
  {#if report.unavailableSince}<p role="status">Weather unavailable since {report.unavailableSince}. Showing last good data where available.</p>
  {:else if report.locationSet && !report.forecastFetchedAt}<p role="status">Weather is waiting for its background refresh.</p>{/if}
  {#if report.alerts.length}
    <ul aria-label="Weather alerts">
      {#each report.alerts as alert (alert.id)}
        <li><a href={'/plants/' + alert.plant_id}>{alert.plant_name}</a>: Low temperature {alert.payload.minC} °C, below {alert.payload.thresholdC} °C hardiness, {alert.starts_on} to {alert.ends_on}. Bring it in or protect it.
          {#if alert.payload.hardiness?.ruleId === 'hardiness.usda_uncertain@1'}<p>USDA zone conversion is uncertain.</p>{/if}
          <p>Hardiness source: {alert.payload.hardiness?.source.replaceAll('_', ' ')} · {alert.payload.hardiness?.value} °C{#if alert.payload.hardiness?.ruleId} · {alert.payload.hardiness.ruleId}{/if}{#if alert.payload.potRuleId} · {alert.payload.potRuleId}{/if}</p>
        </li>
      {/each}
    </ul>
  {/if}
  {/if}
  {#if showRain && report.rain}
    <p>It rained: {report.rain.precipitationMm} mm today or yesterday.</p>
    {#if report.rain.batchId}<p>Marked watered.</p>
    {:else}<button disabled={busy} onclick={water}>Mark {report.rain.plantCount} outdoor plants watered</button>{/if}
  {/if}
  {#if rainOnly && !report.rain && !report.lastRainBatch}<p role="status">Rain watering is no longer available for this report.</p>{/if}
  {#if showRain && report.lastRainBatch}<p><button disabled={busy} onclick={undo}>Undo rain watering</button></p>{/if}
  {#if message}<p role="status">{message}</p>{/if}
  {#if !rainOnly && report.forecastFetchedAt}<p>Forecast from Open-Meteo · Fetched {report.forecastFetchedAt}</p>{/if}
</section>
{/if}
<style>
  section { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; overflow-wrap: anywhere; }
  h2 { margin-top: 0; }
  ul { padding-left: 1.25rem; }
  li { margin-bottom: 1rem; }
  button { min-height: 44px; }
</style>
