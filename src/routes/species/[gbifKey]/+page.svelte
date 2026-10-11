<script lang="ts">
  import CareCard from '$lib/CareCard.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>{data.care.species.canonical_name} | Fytorium</title></svelte:head>
<a href="/plants">Plants</a>
<h1>{data.care.species.canonical_name}</h1>
<p>{data.care.species.scientific_name}</p>
<p>Species identity from <a href={'https://www.gbif.org/species/' + data.care.species.gbif_key}>GBIF</a>, CC BY 4.0.</p>
<CareCard gbifKey={data.care.species.gbif_key} profile={data.care.profile} fetchStates={data.care.fetches} canEdit={data.canEdit} />
