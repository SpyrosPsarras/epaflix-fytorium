<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import { careEventLabel, taskKey, taskPresentation, type CareEvent, type ListedTask, type TaskList } from '$lib/tasks';
  import type { Task } from '$lib/schedule/engine';
  let { list, sitter = false, memberId }: { list: TaskList; sitter?: boolean; memberId?: string } = $props();
  let handled = $state<Record<string, { event: CareEvent; task: Task }>>({});
  let restored = $state<Record<string, Task>>({});
  let requestIds = $state<Record<string, string>>({});
  let busy = $state<Record<string, boolean>>({});
  let message = $state('');
  let saved = $state('');
  let saving = $state(false);
  const title = $derived(list.view === 'upcoming' ? 'Upcoming' : 'Today');
  function actionFor(row: ListedTask) {
    const id = row.plant_id + ':' + taskKey(row);
    return handled[id] ?? (!restored[id] && row.completed_event ? { event: row.completed_event, task: row } : undefined);
  }
  async function act(row: ListedTask, action: 'complete' | 'snooze' | 'skip' | 'undo') {
    const id = row.plant_id + ':' + taskKey(row);
    const done = actionFor(row);
    const key = id + ':' + action;
    busy[id] = true;
    message = '';
    requestIds[key] ??= crypto.randomUUID();
    try {
      const response = await fetch(action === 'undo' && done ? done.event.batch_id ? '/api/task-batches/' + done.event.batch_id + '/undo' : '/api/care-events/' + done.event.id + '/undo' : '/api/plants/' + row.plant_id + '/tasks/' + taskKey(row) + '/' + action, {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: action === 'undo' ? undefined : JSON.stringify({ request_id: requestIds[key] }),
      });
      const result = await response.json();
      if (!response.ok) { message = result.message ?? 'Could not update the task. Try again.'; return; }
      if (action === 'undo') {
        if (done?.event.batch_id) {
          handled = {}; restored = {}; requestIds = {};
          await invalidateAll();
          return;
        }
        restored[id] = result.task;
        delete handled[id];
        for (const command of ['complete', 'snooze', 'skip']) delete requestIds[id + ':' + command];
        if (row.completed_event) await invalidateAll();
      } else handled[id] = result;
    } catch { message = 'Could not reach Fytorium. Try again.'; }
    finally { busy[id] = false; }
  }
  async function saveView(event: SubmitEvent) {
    event.preventDefault();
    const form = new FormData(event.currentTarget as HTMLFormElement);
    saving = true;
    message = ''; saved = '';
    try {
      const response = await fetch('/api/tasks/settings', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ sort: form.get('sort'), filter: form.get('filter'), show_completed: form.has('show_completed') }) });
      if (!response.ok) { message = 'Could not save the task view.'; return; }
      await invalidateAll();
      handled = {}; restored = {};
      saved = 'Task view saved.';
    } catch { message = 'Could not reach Fytorium. Try again.'; }
    finally { saving = false; }
  }
</script>
<section aria-labelledby="task-list-title">
  <h2 id="task-list-title">{title}</h2>
  <p>{list.today}{#if list.view === 'upcoming'} · Next 14 calendar days, including today{/if}</p>
  <nav><a href="/">Today</a> · <a href="/upcoming">Upcoming</a></nav>
  {#if !sitter}
  <form onsubmit={saveView}>
    <label for="task-sort">Sort tasks</label><select id="task-sort" name="sort" value={list.settings.sort}><option value="task">Task type</option><option value="plant">Plant</option><option value="site">Site</option></select>
    <label for="task-filter">Filter location</label><select id="task-filter" name="filter" value={list.settings.filter}><option value="all">All</option><option value="indoor">Indoor</option><option value="outdoor">Outdoor</option></select>
    <label class="check"><input name="show_completed" type="checkbox" checked={list.settings.show_completed} /> Show completed</label>
    <button disabled={saving}>Save task view</button>
  </form>
  {/if}
  {#if saved}<p role="status">{saved}</p>{/if}
  {#if message}<p role="alert">{message}</p>{/if}
  {#if list.sections.length === 0}<p>No tasks in this view.</p>{/if}
  {#each list.sections as section (section.key)}
    <h3>{section.label}</h3>
    {#if section.tasks.length === 0}<p>No tasks due today.</p>{/if}
    {#each section.tasks as row (row.plant_id + ':' + taskKey(row))}
      {@const rules = taskPresentation(row)}
      {@const id = row.plant_id + ':' + taskKey(row)}
      {@const done = actionFor(row)}
      {@const task = done?.task ?? restored[id] ?? row}
      <article data-task-type={taskKey(row)} aria-label={rules.label + ' for ' + row.plant_name}>
        <div>
          <a href={'/plants/' + row.plant_id}>{row.plant_name}</a>
          <p>{rules.label}{#if row.site_name} · {row.site_name}{/if}</p>
          {#if done}
            <p role="status">{careEventLabel(done.event.kind, 'Done')}{#if task.status === 'due'} · Next {rules.action}: {task.dueOn}{/if}</p>
          {:else if task.status === 'due'}
            {#if task.lateDays > 0}<span class="late">{task.lateDays}d late</span>
            {:else}<span>{task.dueOn === list.today ? 'Due today' : 'Due ' + task.dueOn}</span>{/if}
          {/if}
        </div>
        <div class="actions">
          {#if done}{#if !sitter || done.event.by_member === memberId}<button disabled={busy[id]} onclick={() => act(row, 'undo')}>Undo</button>{/if}
          {:else}
            <button disabled={busy[id]} onclick={() => act(row, 'complete')}>Done</button>
            {#if task.status === 'due' && task.dueOn <= list.today}<button disabled={busy[id]} onclick={() => act(row, 'snooze')}>Snooze 2 days</button>{/if}
            <button disabled={busy[id]} onclick={() => act(row, 'skip')}>Skip</button>
            {#if !sitter && row.task_type === 'repot'}<a href={'/plants/' + row.plant_id + '/repot'}>Repot with new pot and soil</a>{/if}
          {/if}
        </div>
      </article>
    {/each}
  {/each}
</section>
<style>
  section { margin: 1.5rem 0; }
  article { padding: 1rem; margin: .75rem 0; border-radius: .75rem; background: white; overflow-wrap: anywhere; }
  article a { font-weight: 600; }
  article p { margin: .25rem 0; }
  .actions { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: .75rem; align-items: center; }
  .late { display: inline-block; color: #9f1239; background: #ffe4e6; padding: .15rem .5rem; border-radius: .5rem; }
  form { display: grid; gap: .75rem; margin: 1rem 0; }
  label { display: grid; gap: .25rem; }
  select { font: inherit; padding: .75rem; max-width: 100%; border-radius: .5rem; }
  .check { display: flex; align-items: center; gap: .5rem; }
</style>
