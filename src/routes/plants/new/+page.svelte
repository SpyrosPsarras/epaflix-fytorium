<script lang="ts">
  import PlantForm from '$lib/PlantForm.svelte';
  import { nameLanguages } from '$lib/names';
  import type { Candidate, SpeciesSearch } from '$lib/plants';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let query = $state('');
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
</script>
<svelte:head><title>Add plant | Fytorium</title></svelte:head>
<a href="/plants">Plants</a>
<h1>Add plant</h1>
<form onsubmit={search}>
  <label for="species-name">Species name</label>
  <input id="species-name" bind:value={query} required maxlength="200" placeholder="Common or scientific name, English, Norwegian or Greek" />
  <button disabled={busy}>{busy ? 'Searching…' : 'Search species'}</button>
</form>
{#if message}<p class="error" role="alert">{message}</p>{/if}
{#if result && !chosen}
  <h2>Choose a species</h2>
  {#each result.candidates as candidate, index}
    <article>
      <p>{candidate.scientific_name}</p>
      {#if candidate.matched_name && candidate.matched_language}<p>Matched {candidate.matched_name} · {nameLanguages[candidate.matched_language]}</p>{/if}
      <p>{candidate.match_type === null ? 'Stored species' : candidate.match_type.toLowerCase()}{candidate.confidence === null ? '' : ' · ' + candidate.confidence + '% confidence'} · {candidate.rank.toLowerCase()}{index > 0 ? ' · alternative' : ''}</p>
      <button onclick={() => { chosen = candidate; confirmed = false; }}>Choose {candidate.canonical_name}</button>
    </article>
  {/each}
{/if}
{#if chosen}
  {#if chosen.requires_confirmation && !confirmed}
    <section><h2>Confirm species</h2><p>This match needs your confirmation. Is {chosen.scientific_name} the species you meant?</p>
      <button onclick={() => confirmed = true}>Confirm {chosen.canonical_name}</button>
      <button onclick={() => chosen = undefined}>Back to candidates</button>
    </section>
  {:else}
    <h2>{chosen.canonical_name}</h2>
    <p>{chosen.scientific_name}</p>
    <PlantForm sites={data.sites} candidate={chosen} searchId={result?.search_id} {confirmed} />
  {/if}
{/if}
<p>Names from <a href="https://www.gbif.org">GBIF</a>, CC BY 4.0.</p>
<style>
  label { display: block; margin-top: 1rem; }
  input { box-sizing: border-box; width: 100%; font: inherit; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; margin-bottom: 1rem; }
  input:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  article, section { background: white; border-radius: .75rem; padding: 1rem; margin-top: 1rem; overflow-wrap: anywhere; }
  section button { margin: .25rem; }
  .error { color: #991b1b; }
</style>
