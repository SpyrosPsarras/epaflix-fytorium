<script lang="ts">
  import SiteForm from '$lib/SiteForm.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>

<svelte:head><title>Edit site | Fytorium</title></svelte:head>
<h1>Edit site</h1>
{#key data.site.id}<SiteForm site={data.site} />{/key}
