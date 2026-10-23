<script lang="ts">
  import FeedSettings from '$lib/FeedSettings.svelte';
  import WeatherLocationForm from '$lib/WeatherLocationForm.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Household settings | Fytorium</title></svelte:head>
<a href="/">Home</a>
<h1>Household settings</h1>
<p><a href="/settings/notifications">My notifications</a></p>
<FeedSettings settings={data.feedSettings} />
<WeatherLocationForm location={data.location} canEdit={data.canEditLocation} />
{#if data.isOwner}<p><a href="/species/unmatched">Open Plantbook unmatched species</a></p>{/if}
