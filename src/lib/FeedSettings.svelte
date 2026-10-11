<script lang="ts">
  import { untrack } from 'svelte';
  import { invalidateAll } from '$app/navigation';
  import { feedKinds, feedLabels, type FeedSettings } from '$lib/feed';
  let { settings }: { settings: FeedSettings } = $props();
  let selected = $state<FeedSettings>(untrack(() => ({ ...settings })));
  let busy = $state(false), message = $state(''), failure = $state('');
  $effect(() => { selected = { ...settings }; });
  async function save(event: SubmitEvent) {
    event.preventDefault(); busy = true; message = failure = '';
    try {
      const response = await fetch('/api/feed/settings', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(selected) });
      if (!response.ok) { failure = (await response.json()).message ?? 'Could not save feed settings.'; return; }
      message = 'Feed settings saved.';
      await invalidateAll();
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<section id="feed-settings" aria-labelledby="feed-settings-title">
  <h2 id="feed-settings-title">My home feed</h2>
  <p>Choose the cards you want to see. Other household members keep their own choices.</p>
  <form onsubmit={save}>
    {#each feedKinds as kind}<label><input type="checkbox" bind:checked={selected[kind]} /> {feedLabels[kind]}</label>{/each}
    {#if failure}<p role="alert">{failure}</p>{/if}
    {#if message}<p role="status">{message}</p>{/if}
    <button disabled={busy}>{busy ? 'Saving…' : 'Save my feed'}</button>
  </form>
</section>
<style>
  section { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; }
  h2 { margin-top: 0; }
  label { display: flex; gap: .5rem; align-items: center; min-height: 44px; }
  input { width: auto; }
  button { margin-top: .75rem; min-height: 44px; }
</style>
