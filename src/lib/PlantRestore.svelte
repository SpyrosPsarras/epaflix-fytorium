<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import type { Plant } from '$lib/plants';
  import type { ListedSite } from '$lib/sites';
  let { plant, sites }: { plant: Plant; sites: ListedSite[] } = $props();
  let siteId = $state('');
  $effect(() => { siteId = plant.site_id ?? ''; });
  let busy = $state(false);
  let failure = $state('');
  async function restore(event: SubmitEvent) {
    event.preventDefault(); busy = true; failure = '';
    try {
      const response = await fetch('/api/plants/' + plant.id + '/restore', {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ site_id: siteId }),
      });
      if (!response.ok) { failure = (await response.json()).message ?? 'Could not restore the plant.'; return; }
      await invalidateAll();
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<form onsubmit={restore}>
  <p>Restore this plant to active lists and care tasks. Its history and photos stay.</p>
  <label for="restore-site">Restore to site</label>
  <select id="restore-site" bind:value={siteId} required>
    <option value="">Choose a site</option>
    {#each sites as site}<option value={site.id}>{site.name}</option>{/each}
  </select>
  {#if !sites.length}<p><a href="/sites/new">Create a site</a> before restoring this plant.</p>{/if}
  {#if failure}<p role="alert">{failure}</p>{/if}
  <button disabled={busy || !siteId}>{busy ? 'Restoring…' : 'Restore plant'}</button>
</form>
<style>
  form { padding: 1rem; background: white; border-radius: .75rem; }
  label { display: block; }
  select { width: 100%; box-sizing: border-box; font: inherit; padding: .75rem; margin: .5rem 0; }
</style>
