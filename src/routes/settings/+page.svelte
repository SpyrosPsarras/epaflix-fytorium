<script lang="ts">
  import WeatherLocationForm from '$lib/WeatherLocationForm.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let enabled = $state(true);
  $effect(() => { enabled = data.settings.misting_enabled; });
  let busy = $state(false);
  let message = $state('');
  let failure = $state('');
  async function save(event: SubmitEvent) {
    event.preventDefault();
    busy = true;
    message = failure = '';
    try {
      const response = await fetch('/api/household/settings', {
        method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ misting_enabled: enabled }),
      });
      const result = await response.json();
      if (!response.ok) { failure = result.message ?? 'Could not save household settings.'; return; }
      message = 'Household settings saved.';
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<svelte:head><title>Household settings | Fytorium</title></svelte:head>
<a href="/">Home</a>
<h1>Household settings</h1>
<p><a href="/settings/notifications">My notifications</a></p>
<WeatherLocationForm location={data.location} canEdit={data.canEditLocation} />
{#if data.isOwner}<p><a href="/species/unmatched">Open Plantbook unmatched species</a></p>{/if}
<section>
  <h2>Misting</h2>
  <form onsubmit={save}>
    <label><input type="checkbox" bind:checked={enabled} /> Misting reminders for the household</label>
    <p>Turning this off hides misting tasks for every plant. Each plant keeps its own switch.</p>
    {#if failure}<p role="alert">{failure}</p>{/if}
    {#if message}<p role="status">{message}</p>{/if}
    <button disabled={busy}>{busy ? 'Saving…' : 'Save household settings'}</button>
  </form>
</section>
<style>
  section { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; }
  h2 { margin-top: 0; }
  label { display: flex; gap: .5rem; align-items: center; }
  input { width: auto; }
</style>
