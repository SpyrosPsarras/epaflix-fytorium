<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import type { HouseholdLocation } from '$lib/weather';
  let { location, canEdit }: { location: HouseholdLocation | null; canEdit: boolean } = $props();
  let latitude = $state(''), longitude = $state(''), label = $state('');
  let busy = $state(false), message = $state(''), failure = $state('');
  $effect(() => { latitude = location ? String(location.latitude) : ''; longitude = location ? String(location.longitude) : ''; label = location?.label ?? ''; });
  async function save(event: SubmitEvent) {
    event.preventDefault();
    busy = true; message = failure = '';
    try {
      const value = latitude === '' && longitude === '' ? null : { latitude: latitude === '' ? null : Number(latitude), longitude: longitude === '' ? null : Number(longitude), label };
      const response = await fetch('/api/household/location', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ location: value }) });
      const result = await response.json();
      if (!response.ok) { failure = result.message ?? 'Could not save location.'; return; }
      message = 'Location saved. Weather refreshes in the background.';
      await invalidateAll();
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<section aria-labelledby="weather-location-title">
  <h2 id="weather-location-title">Weather location</h2>
  <p>One location for the household. Coordinates are sent to Open-Meteo to fetch local weather and modelled climate.</p>
  {#if canEdit}
    <form onsubmit={save}>
      <label for="latitude">Latitude</label><input id="latitude" type="text" inputmode="decimal" bind:value={latitude} />
      <label for="longitude">Longitude</label><input id="longitude" type="text" inputmode="decimal" bind:value={longitude} />
      <label for="place-label">Place label, optional</label><input id="place-label" bind:value={label} maxlength="200" />
      <p>Leave both coordinates blank to remove the location.</p>
      {#if failure}<p role="alert">{failure}</p>{/if}
      {#if message}<p role="status">{message}</p>{/if}
      <button disabled={busy}>{busy ? 'Saving…' : 'Save location'}</button>
    </form>
  {:else}<p>{location?.label ?? (location ? 'Location set.' : 'No location set.')} Only the owner can change it.</p>{/if}
</section>
<style>
  section { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; }
  h2 { margin-top: 0; }
  label { display: block; margin-top: .75rem; }
  input { box-sizing: border-box; width: 100%; padding: .75rem; font: inherit; }
</style>
