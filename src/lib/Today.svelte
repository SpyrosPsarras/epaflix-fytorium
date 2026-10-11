<script lang="ts">
  import type { CareEvent, ListedTask, TaskList } from '$lib/tasks';
  import { taskTypes, type Task } from '$lib/schedule/engine';
  import { taskRules } from '$lib/schedule/rules';
  let { list }: { list: TaskList } = $props();
  let completed = $state<Record<string, { event: CareEvent; task: Task }>>({});
  let restored = $state<Record<string, Task>>({});
  let requestIds = $state<Record<string, string>>({});
  let busy = $state<Record<string, boolean>>({});
  let message = $state('');

  async function act(row: ListedTask) {
    const id = row.plant_id + ':' + row.task_type;
    const done = completed[id];
    busy[id] = true;
    message = '';
    requestIds[id] ??= crypto.randomUUID();
    try {
      const response = await fetch(done ? '/api/care-events/' + done.event.id + '/undo' : '/api/plants/' + row.plant_id + '/tasks/' + row.task_type + '/complete', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: done ? undefined : JSON.stringify({ request_id: requestIds[id] }),
      });
      const result = await response.json();
      if (!response.ok) { message = result.message ?? 'Could not update the task. Try again.'; return; }
      if (done) {
        restored[id] = result.task;
        delete completed[id];
        delete requestIds[id];
      } else completed[id] = result;
    } catch { message = 'Could not reach Fytorium. Try again.'; }
    finally { busy[id] = false; }
  }
</script>
<section aria-labelledby="today-title">
  <h2 id="today-title">Today</h2>
  <p>{list.today}</p>
  {#if message}<p role="alert">{message}</p>{/if}
  {#each taskTypes as taskType}
    {@const rules = taskRules[taskType]}
    <h3>{rules.label}</h3>
    {#if list.groups[taskType].length === 0}<p>No {rules.action} due today.</p>{/if}
    {#each list.groups[taskType] as row (row.plant_id + ':' + row.task_type)}
      {@const id = row.plant_id + ':' + row.task_type}
      {@const done = completed[id]}
      {@const task = done?.task ?? restored[id] ?? row}
      <article>
        <div>
          <a href={'/plants/' + row.plant_id}>{row.plant_name}</a>
          <p>{row.site_name}</p>
          {#if task.status === 'due'}
            {#if done}<p role="status">Done · Next {rules.action}: {task.dueOn}</p>
            {:else if task.lateDays > 0}<span class="late">{task.lateDays}d late</span>
            {:else}<span>Due today</span>{/if}
          {/if}
        </div>
        <button disabled={busy[id]} onclick={() => act(row)} aria-label={done ? 'Undo' : 'Done'}>{busy[id] ? 'Saving…' : done ? 'Undo' : 'Done'}</button>
      </article>
    {/each}
  {/each}
</section>
<style>
  section { margin: 1.5rem 0; }
  article { display: flex; gap: .75rem; align-items: center; justify-content: space-between; padding: 1rem; margin: .75rem 0; border-radius: .75rem; background: white; }
  article div { min-width: 0; overflow-wrap: anywhere; }
  article a { font-weight: 600; }
  article p { margin: .25rem 0; }
  button { flex-shrink: 0; }
  .late { display: inline-block; color: #9f1239; background: #ffe4e6; padding: .15rem .5rem; border-radius: .5rem; }
</style>
