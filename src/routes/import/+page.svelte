<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import SpeciesPicker from '$lib/SpeciesPicker.svelte';
  import SpeciesPhoto from '$lib/SpeciesPhoto.svelte';
  import SpeciesTile from '$lib/SpeciesTile.svelte';
  import type { ImportSuggestion } from '$lib/import';
  import Thumbnail from '$lib/Thumbnail.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let code = $state('');
  let busy = $state(false);
  let message = $state('');
  let complete = $state(false);
  let searching = $state<Record<string, boolean>>({});
  const endpoint = '/api/import/planta';
  let suggestions = $state<Record<string, ImportSuggestion>>({});
  $effect(() => {
    const controller = new AbortController();
    for (const row of data.rows.filter(row => row.status === 'review')) {
      fetch(`${endpoint}/${row.id}/suggestion`, { method: 'POST', signal: controller.signal })
        .then(async response => {
          if (response.ok) {
            const suggestion = await response.json();
            if (!controller.signal.aborted) suggestions[row.id] = suggestion;
          }
        }).catch(() => {});
    }
    return () => controller.abort();
  });
  const reviewRows = $derived(data.rows.filter(row => row.status === 'review'));
  const orderedRows = $derived([...reviewRows, ...data.rows.filter(row => row.status !== 'review')]);
  const rowName = (row: PageData['rows'][number]) => row.mapped.nickname ?? row.mapped.scientific_name ?? row.planta_plant_id;
  function searchLabel(row: PageData['rows'][number]) {
    if (row.status === 'skipped') return 'Undo skip';
    if (row.status !== 'review') return 'Change species or skip';
    return row.candidates.length ? 'Search for another species' : 'Search for a species';
  }
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
    code = ''; searching = {};
    await send(endpoint, 'POST', { code: entered });
  }
  async function review(id: string, body: object) {
    if (await send(endpoint + '/' + id, 'PUT', body)) searching = {};
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
  <p>Plants marked Needs review need one tap: choose the matching species or skip. Everything else is ready.</p>
  {#snippet reviewSummary()}
    {#if reviewRows.length}
      <p role="status" class="review-summary">{reviewRows.length} {reviewRows.length === 1 ? 'plant still needs' : 'plants still need'} review:
        {#each reviewRows as row, index}{index ? ', ' : ''}<a href={'#import-' + row.id}>{rowName(row)}</a>{/each}
      </p>
    {/if}
  {/snippet}
  {@render reviewSummary()}
  {#each orderedRows as row (row.id)}
    {@const searchOpen = searching[row.id] ?? (row.status === 'review' && row.candidates.length === 0)}
    <article id={'import-' + row.id} class:needs-review={row.status === 'review'}>
      <h3>{rowName(row)}</h3>
      {#if row.status === 'confirmed'}
        <p class="ready"><span aria-hidden="true">✓</span> Ready to import</p>
        {#if row.selected}<div class="selected-species"><SpeciesPhoto gbifKey={row.selected.gbif_key} name={row.selected.canonical_name} showName displayPhoto={false} /></div>{/if}
      {/if}
      <Thumbnail src={row.mapped.has_cover ? `${endpoint}/${row.id}/cover` : null} alt={'Planta photo of ' + rowName(row)} placeholder="No Planta photo" size={row.status === 'review' ? '24rem' : '10rem'} />
      <p>{row.status === 'confirmed' ? '' : row.mapped.scientific_name + ' · '}{row.mapped.site_name}</p>
      <p>Pot: {row.mapped.pot_type.replaceAll('_', ' ')} · {row.mapped.pot_size_cm ?? 'unknown'} cm · Soil: {row.mapped.soil_type.replaceAll('_', ' ')}</p>
      {#if row.cover_error}<p role="status">Cover not imported: {row.cover_error} Fetch again to retry the cover.</p>{/if}
      {#if row.status === 'committed'}
        <p>Imported</p>
        {#if row.plant_id}<a href={'/plants/' + row.plant_id}>Open imported plant</a>{/if}
      {:else}
        {#if row.status === 'review'}<p class="review-badge">Needs review</p>
        {:else if row.status === 'skipped'}<p>Skipped. This plant will not be imported.</p>{/if}
        {#if row.status === 'review' || (row.status === 'skipped' && searchOpen)}
          {#if row.candidates.length}<h4>Which picture looks like your plant?</h4>
          {:else}<p>No match found. Search for the species or skip this plant.</p>{/if}
          <div class="candidates">
            {#each [...row.candidates, ...(suggestions[row.id]?.candidates ?? [])] as candidate (candidate.gbif_key)}
              <SpeciesTile {candidate} suggested={suggestions[row.id]?.suggested_gbif_key === candidate.gbif_key} disabled={busy} onclick={() => review(row.id, { gbif_key: candidate.gbif_key, confirmed: true })} />
            {/each}
          </div>
          {#if suggestions[row.id]?.genus}
            {@const genus = suggestions[row.id].genus!}
            <button class="genus" disabled={busy} onclick={() => review(row.id, { gbif_key: genus.gbif_key, confirmed: true })}>Not sure: import as {genus.canonical_name} (any kind){#if suggestions[row.id].suggested_gbif_key === genus.gbif_key}<br />Looks most like your photo{/if}</button>
            <p>You can change the species later.</p>
          {/if}
        {/if}
        {#if row.status === 'review'}<button disabled={busy} onclick={() => review(row.id, { skip: true })}>Skip plant</button>{/if}
        <button class="text-link" aria-expanded={searchOpen} disabled={busy} onclick={() => searching[row.id] = !searchOpen}>{searchLabel(row)}</button>
        {#if searchOpen}
          {#if row.status === 'skipped'}<p>Undo skip: choose a picture or search for a species below.</p>{/if}
          {#if row.status === 'confirmed'}<button disabled={busy} onclick={() => review(row.id, { skip: true })}>Skip plant</button>{/if}
          <SpeciesPicker hideMatchDetails>
            {#snippet children(chosen, searchId, confirmed)}
              <button disabled={busy} onclick={() => review(row.id, { search_id: searchId, gbif_key: chosen.gbif_key, confirmed })}>Use searched species</button>
            {/snippet}
          </SpeciesPicker>
        {/if}
      {/if}
    </article>
  {/each}
  {@render reviewSummary()}
  <button disabled={busy || !data.rows.some(row => row.can_commit)} onclick={commit}>Import confirmed plants</button>
{/if}
<p>Species names from <a href="https://www.gbif.org">GBIF</a>, CC BY 4.0. New sites use default conditions. Complete them in <a href="/sites">Sites</a>.</p>
<style>
  form, article { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; overflow-wrap: anywhere; }
  label { display: block; margin-bottom: .25rem; }
  input { box-sizing: border-box; width: 100%; min-width: 0; padding: .75rem; font: inherit; margin-bottom: 1rem; border: 1px solid #78716c; border-radius: .5rem; }
  input:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  h3 { margin-top: 0; }
  article { scroll-margin-top: 1rem; border: 2px solid transparent; }
  .needs-review { border-color: #b45309; }
  .review-summary, .review-badge { background: #fef3c7; color: #78350f; padding: .75rem; border-radius: .5rem; }
  .review-badge { display: inline-block; font-weight: bold; }
  .candidates { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr)); gap: 1rem; }
  .ready { display: flex; align-items: center; gap: .75rem; color: #166534; font-size: 1.5rem; font-weight: bold; }
  .ready span { font-size: 3rem; }
  .selected-species { font-size: .875rem; color: #57534e; }
  .text-link { background: none; color: #57534e; border: 0; padding: .5rem 0; text-decoration: underline; font-size: .875rem; min-height: 48px; }
  .genus { margin-top: 1rem; min-height: 48px; }
  .error { color: #991b1b; }
</style>
