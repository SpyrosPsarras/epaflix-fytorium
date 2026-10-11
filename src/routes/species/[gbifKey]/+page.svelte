<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import { nameLanguages, type NameLanguage } from '$lib/names';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let name = $state('');
  let language = $state<NameLanguage>('en');
  let message = $state('');
  let busy = $state(false);
  $effect(() => {
    if (data.species.name_fetch_needed) {
      const timer = setTimeout(() => { void invalidateAll(); }, 3000);
      return () => clearTimeout(timer);
    }
  });
  async function change(path: string, method: string, body?: unknown) {
    busy = true; message = '';
    try {
      const response = await fetch('/api/species/' + data.species.gbif_key + '/names' + path, {
        method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
      });
      if (!response.ok) { message = (await response.json()).message ?? 'Could not update names.'; return; }
      if (method === 'POST' && path === '') name = '';
      if (path === '/refresh') message = 'Names queued for refresh.';
      await invalidateAll();
    } catch { message = 'Could not reach the server. Try again.'; }
    finally { busy = false; }
  }
</script>
<svelte:head><title>{data.species.canonical_name} names | Fytorium</title></svelte:head>
<a href="/plants">Plants</a>
<h1>{data.species.scientific_name}</h1>
<p>GBIF key: {data.species.gbif_key}</p>
{#each Object.entries(nameLanguages) as [code, label]}
  <section aria-labelledby={'names-' + code}>
    <h2 id={'names-' + code}>{label}</h2>
    <ul>
      {#each data.species.names.filter(item => item.language === code) as item (item.id)}
        <li><span>{item.name}</span> · Source: {item.source}
          {#if data.canEdit && item.source === 'owner'}
            <button disabled={busy} aria-label={'Remove ' + item.name} onclick={() => change('/' + item.id, 'DELETE')}>Remove</button>
          {/if}
        </li>
      {:else}<li>No names stored yet.</li>{/each}
    </ul>
  </section>
{/each}
{#if data.canEdit}
  <section>
    <h2>Add an owner name</h2>
    <form onsubmit={(event) => { event.preventDefault(); void change('', 'POST', { name, language }); }}>
      <label for="owner-name">Owner name</label>
      <input id="owner-name" bind:value={name} required maxlength="200" />
      <label for="name-language">Language</label>
      <select id="name-language" bind:value={language}>
        {#each Object.entries(nameLanguages) as [code, label]}<option value={code}>{label}</option>{/each}
      </select>
      <button disabled={busy}>Add name</button>
    </form>
    <p>Owner names survive external-name refreshes.</p>
    <button disabled={busy} onclick={() => change('/refresh', 'POST')}>Refresh names</button>
  </section>
{/if}
{#if data.species.name_fetch_needed}<p role="status">Fetching names in the background. Failed sources retry with backoff, up to eight attempts.</p>{/if}
{#if data.species.name_fetch_failed}<p role="status">A name source stopped after eight failures. Refresh names to try again. Stored names remain available.</p>{/if}
{#if message}<p role="status">{message}</p>{/if}
<p><a href={'https://www.gbif.org/species/' + data.species.gbif_key}>Species and names from GBIF (CC BY 4.0)</a></p>
{#if data.species.names.some(item => item.source === 'wikidata')}<p>Wikidata names are CC0.</p>{/if}
<style>
  h1, section { overflow-wrap: anywhere; }
  section { background: white; border-radius: .75rem; padding: 1rem; margin-top: 1rem; }
  h2 { margin-top: 0; }
  ul { padding-left: 1.25rem; }
  li { margin: .75rem 0; }
  li button { margin-top: .5rem; }
  label { display: block; margin-top: 1rem; }
  input, select { box-sizing: border-box; width: 100%; font: inherit; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; margin-bottom: 1rem; }
  input:focus-visible, select:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
</style>
