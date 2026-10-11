<script lang="ts">
  import PlantList from '$lib/PlantList.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Graveyard | Fytorium</title></svelte:head>
<a href="/plants">Plants</a>
<h1>Graveyard</h1>
<p>History and photos stay here, without care tasks. Open a plant to restore it.</p>
<PlantList plants={data.plants} />
