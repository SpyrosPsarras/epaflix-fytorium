<script lang="ts">
  import PlantForm from '$lib/PlantForm.svelte';
  import SpeciesPicker from '$lib/SpeciesPicker.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Add plant | Fytorium</title></svelte:head>
<a href="/plants">Plants</a>
<h1>Add plant</h1>
<SpeciesPicker>
  {#snippet children(candidate, searchId, confirmed)}
    <PlantForm sites={data.sites} {candidate} {searchId} {confirmed} />
  {/snippet}
</SpeciesPicker>
