<script lang="ts">
  import PlantForm from '$lib/PlantForm.svelte';
  import { taskTypes, type TaskType } from '$lib/schedule/engine';
  import { taskRules } from '$lib/schedule/rules';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let mistingEnabled = $state(true);
  let intervals = $state<Partial<Record<TaskType, number | null>>>({});
  $effect(() => {
    mistingEnabled = data.careSettings.misting_enabled;
    intervals = { ...data.careSettings.customIntervals };
  });
  let busy = $state(false);
  let message = $state('');
  let failure = $state('');
  async function saveCare(event: SubmitEvent) {
    event.preventDefault();
    busy = true;
    message = failure = '';
    try {
      const response = await fetch('/api/plants/' + data.plant.id + '/care-settings', {
        method: 'PUT', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ misting_enabled: mistingEnabled, customIntervals: Object.fromEntries(Object.entries(intervals).filter(([, value]) => typeof value === 'number')) }),
      });
      const result = await response.json();
      if (!response.ok) { failure = result.message ?? 'Could not save care settings.'; return; }
      message = 'Care settings saved.';
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<svelte:head><title>Plant settings | Fytorium</title></svelte:head>
<h1>Plant settings</h1>
<p>{data.plant.display_name} · {data.plant.scientific_name}</p>
<p><a href={'/plants/' + data.plant.id}>Back to plant</a></p>
<section aria-labelledby="care-settings-title">
  <h2 id="care-settings-title">Care settings</h2>
  <form onsubmit={saveCare}>
    <label class="toggle"><input type="checkbox" bind:checked={mistingEnabled} /> Misting reminders for this plant</label>
    <p>Misting also requires the household switch and suitable indoor humidity.</p>
    <h3>Custom intervals</h3>
    <p>Whole days from 1 to 365. Leave blank or clear, then save to use the computed interval. Season and applicability rules still apply.</p>
    {#each taskTypes as type}
      <div class="interval">
        <label for={'interval-' + type}>{taskRules[type].label} interval in days</label>
        <div>
          <input id={'interval-' + type} type="number" min="1" max="365" step="1" bind:value={intervals[type]} />
          <button type="button" onclick={() => { delete intervals[type]; }} aria-label={'Clear ' + taskRules[type].label.toLowerCase() + ' interval'}>Clear</button>
        </div>
      </div>
    {/each}
    {#if failure}<p role="alert">{failure}</p>{/if}
    {#if message}<p role="status">{message}</p>{/if}
    <button disabled={busy}>{busy ? 'Saving…' : 'Save care settings'}</button>
  </form>
</section>
{#key data.plant.id}<PlantForm plant={data.plant} sites={data.sites} />{/key}
<style>
  section { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; }
  h2 { margin-top: 0; }
  .toggle { display: flex; gap: .5rem; align-items: center; }
  .toggle input { width: auto; }
  .interval { margin: 1rem 0; }
  .interval label { display: block; margin-bottom: .4rem; }
  .interval div { display: flex; gap: .5rem; }
  .interval input { min-width: 0; flex: 1; }
</style>
