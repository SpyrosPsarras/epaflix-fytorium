<script lang="ts">
  import PlantForm from '$lib/PlantForm.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Plant settings | Fytorium</title></svelte:head>
<h1>Plant settings</h1>
<p>{data.plant.display_name} · {data.plant.scientific_name}</p>
{#key data.plant.id}<PlantForm plant={data.plant} sites={data.sites} />{/key}
