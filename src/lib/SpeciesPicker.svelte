<script lang="ts">
  import type { Snippet } from 'svelte';
  import { nameLanguages } from '$lib/names';
  import { shrinkIdentificationImage } from '$lib/identification-images';
  import { identificationQuotaExhausted } from '$lib/identification';
  import type { Candidate, SpeciesSearch } from '$lib/plants';
  let { children, allowIdentification = false, remaining = undefined }: {
    children: Snippet<[Candidate, string, boolean]>; allowIdentification?: boolean; remaining?: number;
  } = $props();
  let query = $state('');
  let photoMode = $state(false);
  let files = $state<File[]>([]);
  let result = $state<SpeciesSearch | null>(null);
  let chosen = $state<Candidate | undefined>();
  let confirmed = $state(false);
  let message = $state('');
  let busy = $state(false);
  async function search(event: SubmitEvent) {
    event.preventDefault();
    result = null; chosen = undefined; confirmed = false; message = ''; busy = true;
    try {
      const response = await fetch('/api/species/search?q=' + encodeURIComponent(query));
      const body = await response.json();
      if (!response.ok) message = body.message ?? 'Could not search species. Try again.';
      else result = body;
    } catch { message = 'Could not reach the server. Try again.'; }
    finally { busy = false; }
  }
  function addPhotos(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const added = Array.from(input.files ?? []);
    input.value = '';
    if (files.length + added.length > 5) {
      message = 'Choose at most five photos. Remove a photo before adding more.';
      return;
    }
    files = [...files, ...added];
    result = null; chosen = undefined; confirmed = false; message = '';
  }
  function removePhoto(index: number) {
    files = files.filter((_, position) => position !== index);
    result = null; chosen = undefined; confirmed = false; message = '';
  }
  async function identify(event: SubmitEvent) {
    event.preventDefault();
    result = null; chosen = undefined; confirmed = false; message = '';
    if (files.length < 1 || files.length > 5) { message = 'Choose one to five images of the same plant.'; return; }
    busy = true;
    try {
      const form = new FormData();
      for (const file of files) form.append('images', await shrinkIdentificationImage(file), file.name);
      const response = await fetch('/api/identification', { method: 'POST', body: form });
      const body = await response.json();
      if (!response.ok) {
        message = body.message ?? 'Could not identify these photos. Try again.';
        if (response.status === 429) { remaining = 0; message = ''; }
      } else {
        result = body;
        remaining = body.remaining;
      }
    } catch { message = 'Could not reach the server. Try again.'; }
    finally { busy = false; }
  }
</script>
{#if allowIdentification}
  <button disabled={busy} onclick={() => { photoMode = !photoMode; result = null; chosen = undefined; confirmed = false; message = ''; }}>{photoMode ? 'Search by name' : 'Identify from photos'}</button>
  {#if remaining !== undefined && remaining <= 50}
    <p role="status">{remaining === 0 ? identificationQuotaExhausted : remaining + ' identifications remaining today.'}</p>
  {/if}
{/if}
{#if photoMode}
  <form onsubmit={identify}>
    <label for="identification-camera">Take photo</label>
    <input id="identification-camera" type="file" accept="image/*" capture="environment" onchange={addPhotos} disabled={busy} />
    <label for="identification-photos">Choose photos</label>
    <input id="identification-photos" type="file" accept="image/*" multiple onchange={addPhotos} disabled={busy} />
    <ul>
      {#each files as file, index}
        <li>{file.name} <button type="button" disabled={busy} onclick={() => removePhoto(index)}>Remove photo {index + 1}</button></li>
      {/each}
    </ul>
    <p>Take or upload one to five photos of the same plant. Photos are shrunk before upload, with a 15 MB per-photo and 40 MB total upload limit. Metadata is removed before sending to Pl@ntNet. These photos are not saved.</p>
    <button disabled={busy}>{busy ? 'Identifying…' : 'Identify plant'}</button>
  </form>
{:else}
<form onsubmit={search}>
  <label for="species-name">Species name</label>
  <input id="species-name" bind:value={query} required maxlength="200" placeholder="Common or scientific name, English, Norwegian or Greek" />
  <button disabled={busy}>{busy ? 'Searching…' : 'Search species'}</button>
</form>
{/if}
{#if message}<p class="error" role="alert">{message}</p>{/if}
{#if result && !chosen}
  <h2>Choose a species</h2>
  {#each result.candidates as candidate, index}
    <article>
      <p>{candidate.identification_name ?? candidate.scientific_name}</p>
      {#if candidate.source === 'identification'}
        <p>{Math.round(candidate.score * 100)}% identification score</p>
        {#if candidate.common_names?.length}<p>{candidate.common_names.join(', ')}</p>{/if}
        {#if candidate.match_type !== null}
          <p>GBIF accepted name: {candidate.scientific_name} · {candidate.match_type.toLowerCase()} · {candidate.confidence}% confidence · {candidate.rank.toLowerCase()}</p>
        {/if}
      {:else}
        {#if candidate.matched_name && candidate.matched_language}<p>Matched {candidate.matched_name} · {nameLanguages[candidate.matched_language]}</p>{/if}
        <p>{candidate.match_type === null ? 'Stored species' : candidate.match_type.toLowerCase()}{candidate.confidence === null ? '' : ' · ' + candidate.confidence + '% confidence'} · {candidate.rank.toLowerCase()}{index > 0 ? ' · alternative' : ''}</p>
      {/if}
      <button onclick={() => { chosen = candidate; confirmed = false; }}>Choose {candidate.canonical_name}</button>
    </article>
  {/each}
{/if}
{#if chosen && result}
  {#if chosen.requires_confirmation && !confirmed}
    <section><h2>Confirm species</h2>
      {#if chosen.source === 'identification'}<p>Pl@ntNet suggested {chosen.identification_name}, {Math.round(chosen.score * 100)}% identification score.</p>{/if}
      {#if chosen.source === 'identification' && chosen.match_type !== null}<p>GBIF accepted name: {chosen.scientific_name} · {chosen.match_type.toLowerCase()} · {chosen.confidence}% confidence</p>{/if}
      <p>This match needs your confirmation. Is {chosen.scientific_name} the species you meant?</p>
      <button onclick={() => confirmed = true}>Confirm {chosen.canonical_name}</button>
      <button onclick={() => chosen = undefined}>Back to candidates</button>
    </section>
  {:else}
    <h2>{chosen.canonical_name}</h2>
    <p>{chosen.scientific_name}</p>
    {@render children(chosen, result.search_id, confirmed)}
  {/if}
{/if}
<p>Names from <a href="https://www.gbif.org">GBIF</a>, CC BY 4.0.</p>
<style>
  label { display: block; margin-top: 1rem; }
  input { box-sizing: border-box; width: 100%; font: inherit; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; margin-bottom: 1rem; }
  input:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  article, section { background: white; border-radius: .75rem; padding: 1rem; margin-top: 1rem; overflow-wrap: anywhere; }
  section button { margin: .25rem; }
  li { overflow-wrap: anywhere; margin-bottom: .5rem; }
  .error { color: #991b1b; }
</style>
