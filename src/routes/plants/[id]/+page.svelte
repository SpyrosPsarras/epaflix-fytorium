<script lang="ts">
  import { plantLabels, plantBooleans, plantNumbers, plantChoices } from '$lib/plants';
  import { taskTypes } from '$lib/schedule/engine';
  import { taskRules } from '$lib/schedule/rules';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  function showValue(value: unknown): string {
    return typeof value === 'object' ? JSON.stringify(value) : String(value).replaceAll('_', ' ');
  }
  const choices = Object.keys(plantChoices) as (keyof typeof plantChoices)[];
</script>
<svelte:head><title>{data.plant.display_name} | Fytorium</title></svelte:head>
<a href="/plants">Plants</a>
<h1>{data.plant.display_name}</h1>
<p><a href={'/plants/' + data.plant.id + '/settings'}>Plant settings</a></p>
<section>
  <h2>Species and site</h2>
  <p>{data.plant.scientific_name}</p>
  <p><a href={'/species/' + data.plant.gbif_key}>Species care card</a></p>
  <p>{data.plant.canonical_name} · {data.plant.rank.toLowerCase()}</p>
  <p>GBIF key: <a href={'https://www.gbif.org/species/' + data.plant.gbif_key}>{data.plant.gbif_key}</a></p>
  <p>Species data from GBIF, CC BY 4.0</p>
  <p>Site: <a href={'/sites/' + data.plant.site_id}>{data.plant.site_name}</a></p>
  <dl><dt>Nickname</dt><dd>{data.plant.nickname ?? 'Not set'}</dd><dt>Cultivar</dt><dd>{data.plant.cultivar ?? 'Not set'}</dd><dt>Birthday</dt><dd>{data.plant.birthday ?? 'Not set'}</dd></dl>
</section>
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
            <p class="source">Source: {basis.source.replaceAll('_', ' ')}{#if basis.ruleId} · {basis.ruleId}{/if}{#if basis.fetchedAt} · Fetched {basis.fetchedAt}{/if}</p>
          </dd>
        {/each}
      </dl>
    </details>
  {/each}
</section>
<section aria-labelledby="care-history-title">
  <h2 id="care-history-title">Care history</h2>
  {#if data.history.length === 0}<p>No care events yet.</p>{/if}
  <ul>
    {#each data.history as event (event.id)}
      <li class:undone={event.undone_at}>
        <time datetime={event.occurred_on}>{event.occurred_on}</time> · {taskRules[event.task_type].label}
        <p>Completed by {event.by_name}{#if event.undone_at}{' · Undone'}{/if}</p>
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
