<script lang="ts">
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Unmatched species | Fytorium</title></svelte:head>
<a href="/settings">Settings</a>
<h1>Open Plantbook unmatched species</h1>
<p>Rule estimates and other sources still apply. Open a species card to retry its match.</p>
<ul>
  {#each data.species as species}
    <li><a href={'/species/' + species.gbif_key}>{species.canonical_name}</a>: {species.status.replaceAll('_', ' ')}</li>
  {:else}<li>No unmatched species.</li>{/each}
</ul>
