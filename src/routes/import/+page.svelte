<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import SpeciesPicker from '$lib/SpeciesPicker.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let code = $state('');
  let busy = $state(false);
  let message = $state('');
  let complete = $state(false);
  let searching = $state<string | null>(null);
  const endpoint = '/api/import/planta';
  async function send(path: string, method: string, body?: object) {
    busy = true; message = ''; complete = false;
    try {
      const response = await fetch(path, { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
      if (!response.ok) { message = (await response.json()).message ?? 'Could not import. Try again.'; return false; }
      await invalidateAll();
      return true;
    } catch { message = 'Could not reach the server. Your staged plants remain available to retry.'; return false; }
    finally { busy = false; }
  }
  async function fetchPlants(event: SubmitEvent) {
    event.preventDefault();
    const entered = code;
    code = ''; searching = null;
    await send(endpoint, 'POST', { code: entered });
  }
  async function review(id: string, body: object) {
    if (await send(endpoint + '/' + id, 'PUT', body)) searching = null;
  }
  async function commit() {
    complete = await send(endpoint + '/commit', 'POST');
  }
</script>
<svelte:head><title>Import from Planta | Fytorium</title></svelte:head>
<a href="/">Home</a>
<h1>Import from Planta</h1>
<p>Run the real import once with the owner present. Create a one-time app code in the Planta App Portal that day. Fytorium reads the official API, not your password.</p>
<p>A new fetch replaces this review. Re-running updates Planta-sourced species, nickname, cultivar, site, pot and soil, even if you edited them here. Local settings stay. Covers are downloaded only once.</p>
<form onsubmit={fetchPlants}>
  <label for="planta-code">One-time app code</label>
  <input id="planta-code" type="password" autocomplete="off" bind:value={code} required maxlength="1000" />
  <button disabled={busy}>{busy ? 'Working…' : 'Fetch Planta plants'}</button>
</form>
{#if message}<p class="error" role="alert">{message}</p>{/if}
{#if complete}<p role="status">Import complete.</p><p><a href="/plants">View imported plants</a></p>{/if}
{#if data.rows.length}
  <h2>Review plants</h2>
  <p>Confirm a species or skip each plant. The review survives a restart. A failed commit can be retried here without another app code.</p>
  {#each data.rows as row (row.id)}
    <article>
      <h3>{row.mapped.nickname ?? row.mapped.scientific_name ?? row.planta_plant_id}</h3>
      <p>{row.mapped.scientific_name} · {row.mapped.site_name}</p>
      <p>Pot: {row.mapped.pot_type.replaceAll('_', ' ')} · {row.mapped.pot_size_cm ?? 'unknown'} cm · Soil: {row.mapped.soil_type.replaceAll('_', ' ')}</p>
      {#if row.status === 'committed'}
        <p>Imported</p>
        {#if row.plant_id}<a href={'/plants/' + row.plant_id}>Open imported plant</a>{/if}
      {:else}
        {#if row.status === 'review'}<p>Needs review</p>
        {:else if row.status === 'skipped'}<p>Skipped</p>
        {:else}<p>Confirmed species: {row.selected?.canonical_name}</p>{/if}
        {#if row.status === 'review' || searching === row.id}
          <p>Confirm the species you meant, or search for another.</p>
          {#each row.candidates as candidate}
            <section>
              <p>{candidate.scientific_name} · {candidate.match_type?.toLowerCase() ?? 'stored species'}{candidate.confidence === null ? '' : ' · ' + candidate.confidence + '% confidence'}</p>
              <button disabled={busy} onclick={() => review(row.id, { gbif_key: candidate.gbif_key, confirmed: true })}>Confirm {candidate.canonical_name}</button>
            </section>
          {/each}
        {/if}
        <div class="controls">
          <button disabled={busy} onclick={() => searching = searching === row.id ? null : row.id}>Search again</button>
          <button disabled={busy || row.status === 'skipped'} onclick={() => review(row.id, { skip: true })}>Skip plant</button>
        </div>
        {#if searching === row.id}
          <SpeciesPicker>
            {#snippet children(chosen, searchId, confirmed)}
              <button disabled={busy} onclick={() => review(row.id, { search_id: searchId, gbif_key: chosen.gbif_key, confirmed })}>Use searched species</button>
            {/snippet}
          </SpeciesPicker>
        {/if}
      {/if}
    </article>
  {/each}
  <button disabled={busy || data.rows.some(row => row.status === 'review') || !data.rows.some(row => row.status === 'confirmed')} onclick={commit}>Import confirmed plants</button>
{/if}
<p>Species names from <a href="https://www.gbif.org">GBIF</a>, CC BY 4.0. New sites use default conditions. Complete them in <a href="/sites">Sites</a>.</p>
<style>
  form, article { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; overflow-wrap: anywhere; }
  label { display: block; margin-bottom: .25rem; }
  input { box-sizing: border-box; width: 100%; min-width: 0; padding: .75rem; font: inherit; margin-bottom: 1rem; border: 1px solid #78716c; border-radius: .5rem; }
  input:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  h3 { margin-top: 0; }
  .controls { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: 1rem; }
  section { padding-bottom: .5rem; }
  .error { color: #991b1b; }
</style>
