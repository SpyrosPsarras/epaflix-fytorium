<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let role = $state('family');
  let link = $state('');
  let busy = $state(false);
  let failure = $state('');
  let message = $state('');
  async function call(path: string, method: string, body?: unknown) {
    busy = true; failure = ''; message = '';
    try {
      const response = await fetch('/api/household/' + path, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
      if (!response.ok) throw new Error((await response.json()).message ?? 'Could not update members');
      const result = response.status === 204 ? null : await response.json();
      await invalidateAll();
      return result;
    } catch (cause) { failure = cause instanceof Error ? cause.message : 'Could not reach Fytorium'; }
    finally { busy = false; }
  }
  async function invite(event: SubmitEvent) {
    event.preventDefault();
    link = '';
    const form = new FormData(event.currentTarget as HTMLFormElement);
    const result = await call('invites', 'POST', { role, plant_ids: role === 'sitter' ? form.getAll('plant') : [] });
    if (result) link = new URL(result.link, window.location.origin).href;
  }
  async function shares(event: SubmitEvent, id: string) {
    event.preventDefault();
    const form = new FormData(event.currentTarget as HTMLFormElement);
    if (await call('members/' + id, 'PUT', { plant_ids: form.getAll('plant') })) message = 'Shared plants saved.';
  }
</script>
<svelte:head><title>Members | Fytorium</title></svelte:head>
<a href="/">Today</a>
<h1>Members</h1>
<section>
  <h2>Invite a member</h2>
  <form onsubmit={invite}>
    <label>Role <select bind:value={role}><option value="family">Family</option><option value="sitter">Sitter</option></select></label>
    {#if role === 'sitter'}
      <fieldset><legend>Share plants</legend>
        {#each data.plants as plant (plant.id)}<label><input type="checkbox" name="plant" value={plant.id} /> {plant.display_name}</label>{/each}
      </fieldset>
    {/if}
    <button disabled={busy}>Create invite</button>
  </form>
  {#if link}
    <label>Invite link <input readonly value={link} onclick={event => event.currentTarget.select()} /></label>
    <p>Copy this link now. It appears only here, once, and expires after seven days.</p>
    <button onclick={() => { link = ''; }}>Hide link</button>
  {/if}
</section>
{#if failure}<p role="alert">{failure}</p>{/if}
{#if message}<p role="status">{message}</p>{/if}
<section>
  <h2>Household members</h2>
  {#each data.members as member (member.id)}
    <article aria-label={member.display_name}>
      <h3>{member.display_name}</h3><p>Role: {member.role}</p>
      {#if member.role === 'sitter'}
        <form onsubmit={event => shares(event, member.id)}>
          <fieldset><legend>Shared plants for {member.display_name}</legend>
            {#each data.plants as plant (plant.id)}<label><input type="checkbox" name="plant" value={plant.id} checked={member.plant_ids.includes(plant.id)} /> {plant.display_name}</label>{/each}
          </fieldset>
          <button disabled={busy}>Save shared plants</button>
        </form>
      {/if}
      {#if member.id !== data.identity.member.id}
        <button disabled={busy} onclick={() => { if (confirm('Remove this member and all their devices?')) void call('members/' + member.id, 'DELETE'); }}>Remove member</button>
      {/if}
    </article>
  {/each}
</section>
<section>
  <h2>Pending invites</h2>
  {#if !data.invites.length}<p>No pending invites.</p>{/if}
  {#each data.invites as pending (pending.id)}
    <article><p>{pending.role} · Expires {pending.expires_at.slice(0, 10)}</p><button disabled={busy} onclick={() => call('invites/' + pending.id, 'DELETE')}>Revoke invite</button></article>
  {/each}
</section>
<style>
  section, article { background: white; padding: 1rem; margin: 1rem 0; border-radius: .75rem; }
  label { display: flex; align-items: center; gap: .5rem; margin: .75rem 0; }
  input:not([type=checkbox]), select { min-width: 0; width: 100%; padding: .5rem; font: inherit; }
  fieldset { margin: 1rem 0; }
  button { margin-top: .5rem; }
</style>
