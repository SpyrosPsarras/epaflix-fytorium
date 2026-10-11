<script lang="ts">
  import { goto } from '$app/navigation';
  import SpeciesPicker from '$lib/SpeciesPicker.svelte';
  import type { Candidate } from '$lib/plants';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let busy = $state(false);
  let failure = $state('');
  async function save(candidate: Candidate, searchId: string, confirmed: boolean) {
    busy = true; failure = '';
    try {
      const response = await fetch('/api/plants/' + data.plant.id + '/species', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ search_id: searchId, gbif_key: candidate.gbif_key, confirmed }),
      });
      const result = await response.json();
      if (!response.ok) { failure = result.message ?? 'Could not change species.'; return; }
      await goto('/plants/' + data.plant.id, { invalidateAll: true });
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<svelte:head><title>Change species | Fytorium</title></svelte:head>
<p><a href={'/plants/' + data.plant.id + '/settings'}>Plant settings</a></p>
<h1>Change species</h1>
<p>{data.plant.display_name} is currently {data.plant.scientific_name}. Care history, photos and custom intervals will stay. The schedule will use the new species.</p>
<SpeciesPicker>
  {#snippet children(candidate, searchId, confirmed)}
    <button disabled={busy} onclick={() => save(candidate, searchId, confirmed)}>{busy ? 'Saving…' : 'Save species'}</button>
  {/snippet}
</SpeciesPicker>
{#if failure}<p role="alert">{failure}</p>{/if}
