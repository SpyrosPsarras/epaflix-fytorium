<script lang="ts">
  import { goto } from '$app/navigation';
  import { locations, lightLevels, humidityLevels, type Site } from '$lib/sites';
  let { site }: { site?: Site } = $props();
  let errors = $state<Record<string, string>>({});
  let busy = $state(false);
  const temperatures = [
    ['warm_min_c', 'Warm minimum °C'], ['warm_max_c', 'Warm maximum °C'],
    ['cold_min_c', 'Cold minimum °C'], ['cold_max_c', 'Cold maximum °C'],
  ] as const;
  const choices = [
    ['location', 'Location', locations], ['light', 'Light level', lightLevels],
    ['humidity', 'Humidity', humidityLevels],
  ] as const;
  const defaults = { location: 'indoor', light: 'part_sun', humidity: 'normal' };

  async function save(event: SubmitEvent) {
    event.preventDefault();
    const form = new FormData(event.currentTarget as HTMLFormElement);
    const input: Record<string, unknown> = {
      name: form.get('name'), location: form.get('location'), light: form.get('light'), humidity: form.get('humidity'),
      grow_light: form.has('grow_light'), draft: form.has('draft'),
    };
    for (const [field] of temperatures) {
      const value = form.get(field);
      input[field] = value === '' ? null : Number(value);
    }
    busy = true;
    errors = {};
    try {
      const response = await fetch(site ? '/api/sites/' + site.id : '/api/sites', {
        method: site ? 'PUT' : 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(input),
      });
      if (!response.ok) {
        const result = await response.json();
        errors = result.errors ?? { _form: result.message ?? 'Could not save the site. Try again.' };
        return;
      }
      await goto('/sites', { invalidateAll: true });
    } catch {
      errors = { _form: 'Could not reach the server. Try again.' };
    } finally {
      busy = false;
    }
  }
</script>

<form onsubmit={save}>
  {#if errors._form}<p class="error" role="alert">{errors._form}</p>{/if}
  <label for="name">Name</label>
  <input id="name" name="name" required value={site?.name ?? ''} aria-invalid={!!errors.name} aria-describedby={errors.name ? 'name-error' : undefined} />
  {#if errors.name}<p id="name-error" class="error" role="alert">{errors.name}</p>{/if}
  {#each choices as [field, label, values]}
    <label for={field}>{label}</label>
    <select id={field} name={field} aria-invalid={!!errors[field]} aria-describedby={errors[field] ? field + '-error' : undefined}>
      {#each values as value}
        <option {value} selected={value === (site?.[field] ?? defaults[field])}>{value.replaceAll('_', ' ')}</option>
      {/each}
    </select>
    {#if errors[field]}<p id={field + '-error'} class="error" role="alert">{errors[field]}</p>{/if}
  {/each}
  <label class="check"><input type="checkbox" name="grow_light" checked={site?.grow_light ?? false} aria-invalid={!!errors.grow_light} aria-describedby={errors.grow_light ? 'grow-light-error' : undefined} /> Grow light</label>
  {#if errors.grow_light}<p id="grow-light-error" class="error" role="alert">{errors.grow_light}</p>{/if}
  <fieldset>
    <legend>Temperature ranges</legend>
    <p id="periods">Warm period: heating-off or summer half of the year. Cold period: heating-on or winter half. Leave unknown bounds blank.</p>
    {#each temperatures as [field, label]}
      <label for={field}>{label}</label>
      <input id={field} name={field} type="number" step="1" value={site?.[field] ?? ''} aria-invalid={!!errors[field]} aria-describedby={errors[field] ? 'periods ' + field + '-error' : 'periods'} />
      {#if errors[field]}<p id={field + '-error'} class="error" role="alert">{errors[field]}</p>{/if}
    {/each}
  </fieldset>
  <label class="check"><input type="checkbox" name="draft" checked={site?.draft ?? false} aria-invalid={!!errors.draft} aria-describedby={errors.draft ? 'draft-error' : undefined} /> Draft</label>
  {#if errors.draft}<p id="draft-error" class="error" role="alert">{errors.draft}</p>{/if}
  <div class="controls"><button disabled={busy}>{busy ? 'Saving…' : 'Save site'}</button><a href="/sites">Cancel</a></div>
</form>

<style>
  form { background: white; border-radius: .75rem; padding: 1rem; }
  label { display: block; margin-top: 1rem; margin-bottom: .25rem; }
  input:not([type='checkbox']), select { box-sizing: border-box; width: 100%; min-width: 0; font: inherit; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; background: white; color: inherit; }
  input:focus-visible, select:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  input[type='checkbox'] { width: 1.25rem; height: 1.25rem; }
  .check { display: flex; align-items: center; gap: .5rem; padding: .5rem 0; }
  fieldset { min-width: 0; margin: 1rem 0; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; }
  fieldset p { margin-top: .25rem; }
  .controls { display: flex; flex-wrap: wrap; align-items: center; gap: 1rem; margin-top: 1rem; }
  .controls a { padding: .75rem; }
  .error { color: #991b1b; }
</style>
