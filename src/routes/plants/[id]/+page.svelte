<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import PhotoUpdateForm from '$lib/PhotoUpdateForm.svelte';
  import PlantRestore from '$lib/PlantRestore.svelte';
  import PhotoUpdates from '$lib/PhotoUpdates.svelte';
  import { healthLabel } from '$lib/photos';
  import { plantLabels, plantBooleans, plantNumbers, plantChoices } from '$lib/plants';
  import { taskTypes } from '$lib/schedule/engine';
  import { taskPresentation } from '$lib/tasks';
  import { taskRules } from '$lib/schedule/rules';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  function showValue(value: unknown): string {
    return typeof value === 'object' ? JSON.stringify(value) : String(value).replaceAll('_', ' ');
  }
  const choices = Object.keys(plantChoices) as (keyof typeof plantChoices)[];
  let busy = $state(false);
  let message = $state('');
  let failure = $state('');
  let bulkRequestId = $state<string>();
  async function handleAll() {
    busy = true; message = ''; failure = '';
    bulkRequestId ??= crypto.randomUUID();
    try {
      const response = await fetch('/api/plants/' + data.plant.id + '/tasks/complete-all', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ request_id: bulkRequestId }) });
      const result = await response.json();
      if (!response.ok) { failure = result.message ?? 'Could not handle the tasks.'; return; }
      message = 'Handled ' + result.events.length + ' tasks.';
      bulkRequestId = undefined;
      await invalidateAll();
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
  async function undoEvent(id: string) {
    busy = true; failure = ''; message = '';
    try {
      const response = await fetch('/api/care-events/' + id + '/undo', { method: 'POST' });
      if (!response.ok) { failure = (await response.json()).message ?? 'Could not undo the event.'; return; }
      await invalidateAll();
      message = 'Care action undone.';
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<svelte:head><title>{data.plant.display_name} | Fytorium</title></svelte:head>
<a href="/plants">Plants</a>
<h1>{data.plant.display_name}</h1>
{#if data.plant.status === 'graveyard'}
  <p>In the graveyard since {data.plant.graveyard_on}</p>
  <PlantRestore plant={data.plant} sites={data.sites} />
{/if}
<p><a href={'/plants/' + data.plant.id + '/settings'}>Plant settings</a> · <a href="/journal">Journal</a></p>
{#if data.plant.cover_photo_id}
  <img class="cover" src={'/api/photos/' + data.plant.cover_photo_id + '/original'} alt={'Cover photo of ' + data.plant.display_name} />
{:else}<div class="cover placeholder" role="img" aria-label="No plant photo yet">No photo</div>{/if}
<p>Health: {healthLabel(data.plant.health)}</p>
<section aria-labelledby="photo-updates-title">
  <h2 id="photo-updates-title">Photo updates</h2>
  <p><a href={'/plants/' + data.plant.id + '/updates'}>View all updates</a></p>
  <PhotoUpdates updates={data.updates.slice(0, 3)} />
  <h3>Add a photo update</h3>
  <PhotoUpdateForm plantId={data.plant.id} today={data.photoToday} />
</section>
{#if data.plant.status === 'active'}
<button disabled={busy} onclick={handleAll}>Handle all tasks</button>
<p>Completes only tasks due or late today. Undo any event in the batch to undo the whole action.</p>
<p><a href={'/plants/' + data.plant.id + '/repot'}>Repot with new pot and soil</a></p>
{/if}
{#if message}<p role="status">{message}</p>{/if}
{#if failure}<p role="alert">{failure}</p>{/if}
<section aria-labelledby="species-site-title">
  <h2 id="species-site-title">Species and site</h2>
  <p>{data.plant.scientific_name}</p>
  <p><a href={'/species/' + data.plant.gbif_key}>Species care and names</a></p>
  <p>{data.plant.canonical_name} · {data.plant.rank.toLowerCase()}</p>
  <p>GBIF key: <a href={'https://www.gbif.org/species/' + data.plant.gbif_key}>{data.plant.gbif_key}</a></p>
  <p>Species data from GBIF, CC BY 4.0</p>
  <p>Site: {#if data.plant.site_id}<a href={'/sites/' + data.plant.site_id}>{data.plant.site_name}</a>{:else}Site deleted{/if}</p>
  <dl><dt>Nickname</dt><dd>{data.plant.nickname ?? 'Not set'}</dd><dt>Cultivar</dt><dd>{data.plant.cultivar ?? 'Not set'}</dd><dt>Birthday</dt><dd>{data.plant.birthday ?? 'Not set'}</dd></dl>
</section>
{#if data.plant.status === 'active'}
{#each taskTypes as taskType}
  {@const task = data.tasks[taskType]}
  {@const action = taskRules[taskType].action}
  {@const title = taskRules[taskType].label}
  <section aria-labelledby={taskType + '-title'}>
    <h2 id={taskType + '-title'}>{title}</h2>
    {#if task.status === 'due'}
      <p>Next {action}: {task.dueOn}</p>
      <p>Every {task.intervalDays} days</p>
      {#if task.lateDays > 0}<p>{task.lateDays}d late</p>{/if}
    {:else}<p>{title} is not applicable.</p>{/if}
  </section>
{/each}
{#each Object.values(data.tasks.customTasks) as task (task.custom_task_id)}
  <section aria-label={task.name}>
    <h2>{task.name}</h2>
    {#if task.status === 'due'}
      <p>Next {task.name}: {task.dueOn}</p>
      <p>Every {task.intervalDays} days</p>
      {#if task.lateDays > 0}<p>{task.lateDays}d late</p>{/if}
    {/if}
    <details><summary>Based on</summary>{#each task.basedOn as basis}<p>{basis.adjustment} · {showValue(basis.value)} · Source: {basis.source}</p>{/each}</details>
  </section>
{/each}
<section aria-labelledby="based-on-title">
  <h2 id="based-on-title">Based on</h2>
  <p>Starting estimates, not a substitute for checking the plant. Month factors use the northern hemisphere and the anchor date.</p>
  {#each taskTypes as taskType}
    <details>
      <summary>{taskRules[taskType].label}</summary>
      <dl>
        {#each data.tasks[taskType].basedOn as basis}
          <dt>{basis.input}</dt>
          <dd>
            <p>{showValue(basis.value)}{#if basis.factor !== undefined} · ×{basis.factor}{/if}</p>
            <p>{basis.adjustment}</p>
            <p class="source">Source: {basis.source}{#if basis.ruleId} · {basis.ruleId}{/if}{#if basis.fetchedAt} · Fetched {basis.fetchedAt}{/if}</p>
          </dd>
        {/each}
      </dl>
    </details>
  {/each}
</section>
{/if}
<section aria-labelledby="care-history-title">
  <h2 id="care-history-title">Care history</h2>
  {#each data.speciesChanges as change (change.id)}
    <p><time datetime={change.changed_on}>{change.changed_on}</time> · Species changed from {change.previous_name} to {change.next_name} by {change.by_name}</p>
  {/each}
  {#if data.history.length === 0}<p>No care events yet.</p>{/if}
  <ul>
    {#each data.history as event (event.id)}
      <li class:undone={event.undone_at}>
        <time datetime={event.occurred_on}>{event.occurred_on}</time> · {taskPresentation(event).label}
        <p>{event.kind === 'snoozed' ? 'Snoozed' : event.kind === 'skipped' ? 'Skipped' : 'Completed'} by {event.by_name}{#if event.undone_at}{' · Undone'}{/if}</p>
        {#if event.batch_id}<p>Part of a Handle all tasks action.</p>{/if}
        {#if !event.undone_at && data.plant.status === 'active'}<button disabled={busy} onclick={() => undoEvent(event.id)}>Undo</button>{/if}
      </li>
    {/each}
  </ul>
</section>
<section aria-labelledby="attributes-title">
  <h2 id="attributes-title">Pot and plant attributes</h2>
  <dl>
    {#each choices as field}<dt>{plantLabels[field]}</dt><dd>{data.plant[field].replaceAll('_', ' ')}</dd>{/each}
    {#each plantNumbers as field}<dt>{plantLabels[field]}</dt><dd>{data.plant[field] ?? 'Not set'}</dd>{/each}
    {#each plantBooleans as field}<dt>{plantLabels[field]}</dt><dd>{data.plant[field] ? 'Yes' : 'No'}</dd>{/each}
  </dl>
</section>
<style>
  .cover { display: block; width: 100%; height: 16rem; object-fit: contain; border-radius: .75rem; background: #ffedd5; }
  .placeholder { display: grid; place-items: center; color: #57534e; }
  h1, section { overflow-wrap: anywhere; }
  section { background: white; border-radius: .75rem; padding: 1rem; margin-top: 1rem; }
  h2 { margin-top: 0; }
  dt { font-weight: 600; margin-top: .75rem; }
  dd { margin-left: 0; }
  ul { padding-left: 1.25rem; }
  li { margin: .75rem 0; }
  li p { margin: .25rem 0; }
  .undone, .source { color: #57534e; }
  summary { cursor: pointer; font-weight: 600; padding: .75rem 0; }
  dd p { margin: .25rem 0; }
  details { border-top: 1px solid #e7e5e4; }
</style>
