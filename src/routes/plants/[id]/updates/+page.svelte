<script lang="ts">
  import PhotoUpdates from '$lib/PhotoUpdates.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>{data.plant.display_name} updates | Fytorium</title></svelte:head>
<nav><a href={'/plants/' + data.plant.id}>Back to plant</a> · <a href="/journal">Journal</a></nav>
<h1>{data.plant.display_name} updates</h1>
<PhotoUpdates updates={data.updates} />
