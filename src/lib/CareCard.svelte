<script lang="ts">
  import { onMount } from 'svelte';
  import { invalidateAll } from '$app/navigation';
  import { careLabels, careNames, type CareName, type CareProfile } from '$lib/care';
  let { gbifKey, profile, fetchStates, canEdit }: { gbifKey: number; profile: CareProfile; fetchStates: { source: string; status: string }[]; canEdit: boolean } = $props();
  let mounted = $state(false);
  const sourceLabel = (name: string) => name.split('_').map(word => word[0].toUpperCase() + word.slice(1)).join(' ');
  let drafts = $state<Partial<Record<CareName, string>>>({});
  let message = $state('');
  const options: Partial<Record<CareName, string[]>> = {
    light: ['full_shade', 'part_shade', 'part_sun', 'full_sun'], humidityNeed: ['low', 'medium', 'high'],
    cycle: ['annual', 'biennial', 'perennial'], indoor: ['true', 'false'], poisonousToPets: ['true', 'false'], poisonousToHumans: ['true', 'false'],
  };
  const examples: Partial<Record<CareName, string>> = {
    temperatureRangeC: '{"min":15,"max":28}', hardiness: '{"min":9,"max":11}', dimensions: '{"min":30,"max":100,"unit":"cm"}', pruningMonths: '[3,4]', pests: '["Scale insects"]',
  };
  function show(field: CareName, value: unknown): string {
    if (value === null) return 'unknown';
    if (typeof value === 'boolean') return value ? 'Yes' : 'No';
    if (Array.isArray(value)) {
      if (field === 'pruningMonths') return value.map(month => new Intl.DateTimeFormat('en', { month: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(2000, month - 1, 1)))).join(', ');
      return value.length ? value.join(', ') : 'None reported';
    }
    if (typeof value === 'object') {
      const range = value as { min: number; max: number };
      return `${range.min} to ${range.max}`;
    }
    return String(value).replaceAll('_', ' ');
  }
  async function update(field: CareName, method: 'PUT' | 'DELETE') {
    message = '';
    let value: unknown;
    if (method === 'PUT') {
      const draft = drafts[field] ?? '';
      try { value = ['light', 'humidityNeed', 'cycle'].includes(field) ? draft : JSON.parse(draft); }
      catch { message = 'Enter a valid value. Ranges and lists use the JSON format shown.'; return; }
    }
    const response = await fetch(`/api/species/${gbifKey}/care/overrides/${field}`, { method, headers: { 'content-type': 'application/json' }, body: method === 'PUT' ? JSON.stringify({ value }) : undefined });
    if (!response.ok) { message = (await response.json()).message ?? 'Could not update this field'; return; }
    message = method === 'PUT' ? 'Override saved.' : 'Override removed.';
    await invalidateAll();
  }
  async function refresh() {
    const response = await fetch(`/api/species/${gbifKey}/care/refresh`, { method: 'POST' });
    message = response.ok ? 'Refresh queued. Existing overrides are kept.' : 'Could not queue a refresh.';
    await invalidateAll();
  }
  onMount(() => { mounted = true; });
  $effect(() => {
    if (!mounted || !fetchStates.some(state => ['pending', 'fetching'].includes(state.status))) return;
    const timer = setInterval(() => { void invalidateAll(); }, 2000);
    return () => clearInterval(timer);
  });
</script>
<section aria-labelledby="care-card-title">
  <h2 id="care-card-title">Species care</h2>
  <p>Starting estimates. Check the plant and soil before following a watering interval.</p>
  {#each fetchStates as state}
    <p>{state.status === 'disabled' ? sourceLabel(state.source) + ' not configured' : sourceLabel(state.source) + ' fetch: ' + state.status.replaceAll('_', ' ')}</p>
  {/each}
  {#if canEdit}<button onclick={refresh}>Refresh care data</button>{/if}
  <p role="status">{message}</p>
  {#each careNames as field}
    <article aria-label={careLabels[field]}>
      <h3>{careLabels[field]}</h3>
      <p class="value">{show(field, profile[field].value)}</p>
      <p>Source: {profile[field].source.replaceAll('_', ' ')}{#if profile[field].ruleId} · {profile[field].ruleId}{/if}</p>
      <p>Fetch time: {profile[field].fetchedAt ?? 'not fetched'}</p>
      {#if canEdit}
        <details>
          <summary>Override {careLabels[field].toLowerCase()}</summary>
          <form onsubmit={event => { event.preventDefault(); void update(field, 'PUT'); }}>
            <label for={'care-' + field}>Override value for {careLabels[field].toLowerCase()}</label>
            {#if options[field]}
              <select id={'care-' + field} bind:value={drafts[field]} required>
                <option value="">Choose a value</option>
                {#each options[field]! as option}<option value={option}>{option.replaceAll('_', ' ')}</option>{/each}
              </select>
            {:else if field.endsWith('IntervalDays')}
              <input id={'care-' + field} type="text" inputmode="decimal" bind:value={drafts[field]} required />
            {:else}
              <input id={'care-' + field} type="text" bind:value={drafts[field]} placeholder={examples[field]} required />
              <p>JSON format: <code>{examples[field]}</code></p>
            {/if}
            <button type="submit">Save override</button>
            {#if profile[field].source === 'override'}<button type="button" onclick={() => update(field, 'DELETE')}>Remove override</button>{/if}
          </form>
        </details>
      {/if}
    </article>
  {/each}
</section>
<style>
  section { background: white; border-radius: .75rem; padding: 1rem; margin-top: 1rem; overflow-wrap: anywhere; }
  article { border-top: 1px solid #d6d3d1; padding: .5rem 0; }
  h3 { margin-bottom: .5rem; }
  p { margin: .5rem 0; }
  label, input, select { display: block; width: 100%; box-sizing: border-box; }
  input, select, button, summary { min-height: 44px; }
  input, select { padding: .5rem; margin: .5rem 0; }
  button { margin: .25rem .25rem .25rem 0; }
  summary { cursor: pointer; }
  code { white-space: pre-wrap; }
</style>
