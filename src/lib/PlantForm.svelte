<script lang="ts">
  import { goto } from '$app/navigation';
  import { plantChoices, plantLabels, plantBooleans, plantNumbers, type Plant, type Candidate } from '$lib/plants';
  import { healthValues, healthLabel } from '$lib/photos';
  import type { Site } from '$lib/sites';
  let { sites, plant, candidate, searchId, confirmed = false }: { sites: Site[]; plant?: Plant; candidate?: Candidate; searchId?: string; confirmed?: boolean } = $props();
  let errors = $state<Record<string, string>>({});
  let busy = $state(false);
  const choices = Object.keys(plantChoices) as (keyof typeof plantChoices)[];

  async function save(event: SubmitEvent) {
    event.preventDefault();
    const form = new FormData(event.currentTarget as HTMLFormElement);
    const input: Record<string, unknown> = { site_id: form.get('site_id') || null, gbif_key: candidate?.gbif_key, search_id: searchId, confirmed };
    for (const field of ['nickname', 'cultivar', 'birthday']) input[field] = form.get(field) || null;
    input.health = form.get('health') || null;
    for (const field of choices) input[field] = form.get(field);
    for (const field of plantBooleans) input[field] = form.has(field);
    for (const field of plantNumbers) input[field] = form.get(field) === '' ? null : Number(form.get(field));
    busy = true;
    errors = {};
    try {
      const response = await fetch(plant ? '/api/plants/' + plant.id : '/api/plants', { method: plant ? 'PUT' : 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(input) });
      const result = await response.json();
      if (!response.ok) { errors = result.errors ?? { _form: result.message ?? 'Could not save the plant. Try again.' }; return; }
      await goto('/plants/' + result.id, { invalidateAll: true });
    } catch { errors = { _form: 'Could not reach the server. Try again.' }; }
    finally { busy = false; }
  }
</script>

<form onsubmit={save}>
  {#if errors._form}<p class="error" role="alert">{errors._form}</p>{/if}
  {#each [['nickname', 'Nickname'], ['cultivar', 'Cultivar'], ['birthday', 'Birthday']] as [field, label]}
    <label for={field}>{label}</label>
    <input id={field} name={field} type={field === 'birthday' ? 'date' : 'text'} value={plant?.[field as 'nickname' | 'cultivar' | 'birthday'] ?? ''} aria-invalid={!!errors[field]} aria-describedby={errors[field] ? field + '-error' : undefined} />
    {#if errors[field]}<p id={field + '-error'} class="error" role="alert">{errors[field]}</p>{/if}
  {/each}
  <label for="health">Health</label>
  <select id="health" name="health" aria-invalid={!!errors.health} aria-describedby={errors.health ? 'health-error' : undefined}>
    <option value="" selected={!plant?.health}>Not set</option>
    {#each healthValues as health}<option value={health} selected={plant?.health === health}>{healthLabel(health)}</option>{/each}
  </select>
  {#if errors.health}<p id="health-error" class="error" role="alert">{errors.health}</p>{/if}
  <label for="site_id">Site</label>
  <select id="site_id" name="site_id" required={plant?.status !== 'graveyard'} aria-invalid={!!errors.site_id} aria-describedby={errors.site_id ? 'site-error' : undefined}>
    <option value="" selected={!plant?.site_id}>{plant?.status === 'graveyard' ? 'No site' : 'Choose a site'}</option>
    {#each sites as site}<option value={site.id} selected={site.id === plant?.site_id}>{site.name}</option>{/each}
  </select>
  {#if errors.site_id}<p id="site-error" class="error" role="alert">{errors.site_id}</p>{/if}
  {#if sites.length === 0}<p><a href="/sites/new">Create a site</a> before adding a plant.</p>{/if}
  <fieldset><legend>Pot and plant attributes</legend>
    {#each choices as field}
      <label for={field}>{plantLabels[field]}</label>
      <select id={field} name={field} aria-invalid={!!errors[field]} aria-describedby={errors[field] ? field + '-error' : undefined}>
        {#each plantChoices[field] as value}<option {value} selected={value === (plant?.[field] ?? plantChoices[field][0])}>{value.replaceAll('_', ' ')}</option>{/each}
      </select>
      {#if errors[field]}<p id={field + '-error'} class="error" role="alert">{errors[field]}</p>{/if}
    {/each}
    {#each plantNumbers as field}
      <label for={field}>{plantLabels[field]}</label>
      <input id={field} name={field} type="number" min="1" max="2147483647" step="1" value={plant?.[field] ?? ''} aria-invalid={!!errors[field]} aria-describedby={errors[field] ? field + '-error' : undefined} />
      {#if errors[field]}<p id={field + '-error'} class="error" role="alert">{errors[field]}</p>{/if}
    {/each}
    {#each plantBooleans as field}
      <label class="check"><input type="checkbox" name={field} checked={plant?.[field] ?? false} aria-invalid={!!errors[field]} aria-describedby={errors[field] ? field + '-error' : undefined} /> {plantLabels[field]}</label>
      {#if errors[field]}<p id={field + '-error'} class="error" role="alert">{errors[field]}</p>{/if}
    {/each}
  </fieldset>
  <div class="controls"><button disabled={busy || (sites.length === 0 && plant?.status !== 'graveyard')}>{busy ? 'Saving…' : 'Save plant'}</button><a href={plant ? '/plants/' + plant.id : '/plants'}>Cancel</a></div>
</form>
<style>
  form { background: white; border-radius: .75rem; padding: 1rem; }
  label { display: block; margin: 1rem 0 .25rem; }
  input:not([type='checkbox']), select { box-sizing: border-box; width: 100%; min-width: 0; font: inherit; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; background: white; color: inherit; }
  input:focus-visible, select:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  input[type='checkbox'] { width: 1.25rem; height: 1.25rem; }
  .check { display: flex; align-items: center; gap: .5rem; padding: .5rem 0; }
  fieldset { min-width: 0; margin: 1rem 0; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; }
  .controls { display: flex; flex-wrap: wrap; align-items: center; gap: 1rem; margin-top: 1rem; }
  .controls a { padding: .75rem; }
  .error { color: #991b1b; }
</style>
