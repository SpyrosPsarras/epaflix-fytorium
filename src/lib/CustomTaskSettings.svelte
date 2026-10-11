<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import type { CustomTask } from '$lib/custom-tasks';
  let { tasks, plantId, siteId }: { tasks: CustomTask[]; plantId?: string; siteId?: string } = $props();
  let busy = $state(false);
  let message = $state('');
  let failure = $state('');
  async function save(event: SubmitEvent, id?: string) {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    const values = new FormData(form);
    await command(id ? 'PUT' : 'POST', id, { name: values.get('name'), interval_days: Number(values.get('interval_days')), ...(id ? {} : plantId ? { plant_id: plantId } : { site_id: siteId }) });
    if (!id && !failure) form.reset();
  }
  async function command(method: string, id?: string, body?: Record<string, unknown>) {
    busy = true; failure = message = '';
    try {
      const response = await fetch('/api/custom-tasks' + (id ? '/' + id : ''), { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
      if (!response.ok) {
        const result = await response.json();
        failure = result.message ?? (Object.values(result.errors ?? {}).join(' ') || 'Could not save the task.');
        return;
      }
      await invalidateAll();
      message = method === 'DELETE' ? 'Task deleted. Care history kept.' : 'Custom task saved.';
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<section aria-label="Custom tasks">
  <h2>Custom tasks</h2>
  <p>{siteId ? 'Each active plant on this site gets its own task and care history, including plants added later.' : 'These tasks belong to this plant.'} First due date is the later of plant addition or task creation, plus the interval.</p>
  {#if failure}<p role="alert">{failure}</p>{/if}
  {#if message}<p role="status">{message}</p>{/if}
  {#each tasks as task (task.id)}
    <form aria-label={'Edit ' + task.name} onsubmit={event => save(event, task.id)}>
      <label>Task name<input name="name" value={task.name} required maxlength="60" /></label>
      <label>Interval in days<input name="interval_days" type="number" value={task.interval_days} required min="1" max="365" step="1" /></label>
      <div class="actions"><button disabled={busy}>Save task</button><button type="button" disabled={busy} onclick={() => command('DELETE', task.id)}>Delete task</button></div>
    </form>
  {/each}
  <h3>Add custom task</h3>
  <form aria-label="Add custom task" onsubmit={event => save(event)}>
    <label>Task name<input name="name" required maxlength="60" /></label>
    <label>Interval in days<input name="interval_days" type="number" required min="1" max="365" step="1" /></label>
    <button disabled={busy}>Add task</button>
  </form>
  <p>Deleting a task removes its reminders everywhere. Its name and care events stay in history.</p>
</section>
<style>
  section { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; overflow-wrap: anywhere; }
  h2 { margin-top: 0; }
  form, label { display: grid; gap: .5rem; }
  form { margin: 1rem 0; padding-top: .75rem; border-top: 1px solid #e7e5e4; }
  input { min-width: 0; box-sizing: border-box; width: 100%; font: inherit; padding: .75rem; }
  .actions { display: flex; flex-wrap: wrap; gap: .5rem; }
</style>
