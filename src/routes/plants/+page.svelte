<script lang="ts">
  import PlantList from '$lib/PlantList.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Plants | Fytorium</title></svelte:head>
<a href="/">Home</a>
<h1>Plants</h1>
<p><a href="/plants/new">Add plant</a></p>
<PlantList plants={data.plants} />
