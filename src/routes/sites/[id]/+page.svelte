<script lang="ts">
  import PlantList from '$lib/PlantList.svelte';
  import CustomTaskSettings from '$lib/CustomTaskSettings.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>{data.site.name} | Fytorium</title></svelte:head>
<a href="/sites">Sites</a>
<h1>{data.site.name}</h1>
<p>{data.site.location} · {data.site.light.replaceAll('_', ' ')}</p>
<p><a href={'/sites/' + data.site.id + '/edit'}>Edit site</a> · <a href="/plants/new">Add plant</a></p>
<CustomTaskSettings tasks={data.customTasks} siteId={data.site.id} />
<h2>Plants on this site</h2>
<PlantList plants={data.plants} />
<style>h1 { overflow-wrap: anywhere; }</style>
