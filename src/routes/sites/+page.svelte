<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let confirming = $state<string | null>(null);
  let message = $state('');
  let busy = $state(false);

  async function deleteSite(id: string) {
    busy = true;
    message = '';
    try {
      const response = await fetch('/api/sites/' + id, { method: 'DELETE' });
      if (!response.ok) {
        message = (await response.json()).message ?? 'Could not delete the site. Try again.';
        return;
      }
      confirming = null;
      await invalidateAll();
    } catch {
      message = 'Could not reach the server. Try again.';
    } finally {
      busy = false;
    }
  }
</script>

<svelte:head><title>Sites | Fytorium</title></svelte:head>
<a href="/">Home</a>
<h1>Sites</h1>
<p>A site groups plants that share the same conditions.</p>
<a class="create" href="/sites/new">Create site</a>
{#if data.sites.length === 0}
  <p>No sites yet. Create a room, garden or terrace for your plants.</p>
{/if}
{#each data.sites as site (site.id)}
  <article>
    <h2>{site.name}</h2>
    <p>{site.location === 'indoor' ? 'Indoor' : 'Outdoor'} · {site.plant_count} {site.plant_count === 1 ? 'plant' : 'plants'}</p>
    <div class="controls">
      <a href={'/sites/' + site.id}>Edit</a>
      <button disabled={busy} onclick={() => { confirming = site.id; message = ''; }}>Delete</button>
    </div>
    {#if confirming === site.id}
      <p>Delete "{site.name}"? This cannot be undone.</p>
      {#if message}<p class="error" role="alert">{message}</p>{/if}
      <div class="controls">
        <button disabled={busy} onclick={() => deleteSite(site.id)}>{busy ? 'Deleting…' : 'Confirm delete'}</button>
        <button disabled={busy} onclick={() => { confirming = null; message = ''; }}>Cancel</button>
      </div>
    {/if}
  </article>
{/each}

<style>
  .create { display: inline-block; padding: .75rem 1rem; border-radius: .5rem; background: #9a3412; color: white; text-decoration: none; }
  article { background: white; border-radius: .75rem; margin-top: 1rem; padding: 1rem; overflow-wrap: anywhere; }
  h2 { margin-top: 0; }
  .controls { display: flex; flex-wrap: wrap; gap: .75rem; align-items: center; }
  .controls a { padding: .75rem 1rem; }
  .error { color: #991b1b; }
</style>
