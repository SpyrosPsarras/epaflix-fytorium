<script lang="ts">
  import { goto } from '$app/navigation';
  import { plantChoices, plantLabels } from '$lib/plants';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let busy = $state(false);
  let requestId = $state<string>();
  let errors = $state<Record<string, string>>({});
  async function save(event: SubmitEvent) {
    event.preventDefault();
    const form = new FormData(event.currentTarget as HTMLFormElement);
    requestId ??= crypto.randomUUID();
    busy = true; errors = {};
    try {
      const response = await fetch('/api/plants/' + data.plant.id + '/repot', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({
        request_id: requestId, pot_type: form.get('pot_type'), soil_type: form.get('soil_type'), pot_size_cm: form.get('pot_size_cm') ? Number(form.get('pot_size_cm')) : null,
      }) });
      const result = await response.json();
      if (!response.ok) { errors = result.errors ?? { _form: result.message ?? 'Could not save the repot.' }; return; }
      await goto('/plants/' + data.plant.id, { invalidateAll: true });
    } catch { errors = { _form: 'Could not reach Fytorium. Try again.' }; }
    finally { busy = false; }
  }
</script>
<svelte:head><title>Repot {data.plant.display_name} | Fytorium</title></svelte:head>
<a href={'/plants/' + data.plant.id}>Back to plant</a>
<h1>Repot {data.plant.display_name}</h1>
<p>Save the new pot and soil and record repotting today. Undo restores the previous pot and schedule.</p>
<form onsubmit={save}>
  {#each ['pot_type', 'soil_type'] as field}
    {@const key = field as 'pot_type' | 'soil_type'}
    <label for={key}>{plantLabels[key]}</label><select id={key} name={key} value={data.plant[key]} required>{#each plantChoices[key] as choice}<option value={choice}>{choice.replaceAll('_', ' ')}</option>{/each}</select>
    {#if errors[key]}<p role="alert">{errors[key]}</p>{/if}
  {/each}
  <label>Pot size in cm<input name="pot_size_cm" type="number" min="1" max="2147483647" step="1" value={data.plant.pot_size_cm ?? ''} /></label>
  {#if errors.pot_size_cm}<p role="alert">{errors.pot_size_cm}</p>{/if}
  {#if errors._form}<p role="alert">{errors._form}</p>{/if}
  <button disabled={busy}>Save repot</button>
</form>
<style>
  h1 { overflow-wrap: anywhere; }
  form { display: grid; gap: .75rem; background: white; padding: 1rem; border-radius: .75rem; }
  label { display: grid; gap: .25rem; }
  select, input { box-sizing: border-box; font: inherit; width: 100%; padding: .75rem; border: 1px solid #a8a29e; border-radius: .5rem; }
</style>
