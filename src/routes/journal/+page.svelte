<script lang="ts">
  import PhotoUpdates from '$lib/PhotoUpdates.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Plant journal | Fytorium</title></svelte:head>
<nav><a href="/">Home</a> · <a href="/plants">Plants</a></nav>
<h1>Plant journal</h1>
<p>All photo updates, newest taken date first.</p>
<PhotoUpdates updates={data.updates} />
