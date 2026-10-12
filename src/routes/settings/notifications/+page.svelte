<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  let reminderTime = $state('09:00');
  let reminders = $state(false);
  let weather = $state(false);
  let completions = $state(false);
  let busy = $state(false);
  let message = $state('');
  let failure = $state('');
  $effect(() => {
    reminderTime = data.settings.reminder_time;
    reminders = data.settings.reminders_enabled;
    weather = data.settings.weather_alerts_enabled;
    completions = data.settings.completion_notices_enabled;
  });
  async function call(path: string, method = 'POST', body?: unknown) {
    const response = await fetch('/api/notifications/' + path, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!response.ok) {
      const result = await response.json();
      throw new Error(result.message ?? 'Could not update notifications');
    }
    return response.status === 204 ? null : response.json();
  }
  async function act(action: () => Promise<void>) {
    busy = true;
    message = failure = '';
    try { await action(); }
    catch (cause) { failure = cause instanceof Error ? cause.message : 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
  function enable() {
    // Permission is requested only inside this button's click, never on page load.
    return act(async () => {
      if (!('Notification' in window) || !('PushManager' in window) || !('serviceWorker' in navigator)) throw new Error('This browser does not support Web Push. Use HTTPS and a supported browser.');
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') throw new Error('Notifications were not allowed. Change this site’s browser permission to try again.');
      const { publicKey } = await call('key', 'GET');
      const applicationServerKey = Uint8Array.from(atob(publicKey.replace(/-/g, '+').replace(/_/g, '/')), character => character.charCodeAt(0));
      const worker = await navigator.serviceWorker.ready;
      const subscription = await worker.pushManager.getSubscription() ?? await worker.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey });
      await call('subscriptions', 'POST', subscription.toJSON());
      await invalidateAll();
      message = 'Notifications enabled on this device.';
    });
  }
  function save(event: SubmitEvent) {
    event.preventDefault();
    return act(async () => {
      await call('settings', 'PUT', { reminder_time: reminderTime, reminders_enabled: reminders, weather_alerts_enabled: weather, completion_notices_enabled: completions });
      await invalidateAll();
      message = 'Notification settings saved.';
    });
  }
  function remove(id: string) {
    return act(async () => {
      await call('subscriptions/' + id, 'DELETE');
      await invalidateAll();
      message = 'Device removed.';
    });
  }
  function sendTest() {
    return act(async () => {
      const result = await call('test');
      if (!result.sent) throw new Error('No device received the test. Enable notifications or try again later.');
      await invalidateAll();
      message = 'Test notification sent.';
    });
  }
</script>
<svelte:head><title>Notifications | Fytorium</title></svelte:head>
<a href="/">Today</a>
<h1>Notifications</h1>
<p>These choices are yours. Enable each device separately.</p>
{#if !data.configured}<p role="alert">The owner must configure VAPID before enabling notifications.</p>{/if}
<section>
  <h2>Reminders and alerts</h2>
  <form onsubmit={save}>
    <label><input type="checkbox" bind:checked={reminders} /> Daily reminders</label>
    <label>Reminder time <input type="time" required bind:value={reminderTime} /></label>
    <p>Time zone: {data.timezone}. Empty days send nothing. Today's reminder replaces the previous one.</p>
    {#if !data.sitter}<label><input type="checkbox" bind:checked={weather} /> Weather alerts</label>{/if}
    <label><input type="checkbox" bind:checked={completions} /> Task completed by someone else</label>
    <button disabled={busy}>Save notification settings</button>
  </form>
</section>
<section>
  <h2>Devices</h2>
  <button disabled={busy || !data.configured} onclick={enable}>Enable notifications on this device</button>
  <button disabled={busy || !data.configured || !data.devices.length} onclick={sendTest}>Send test notification</button>
  {#if !data.devices.length}<p>No devices enabled.</p>{/if}
  <ul>
    {#each data.devices as device (device.id)}
      <li><span>{device.label}</span><button disabled={busy} aria-label={'Remove ' + device.label} onclick={() => remove(device.id)}>Remove</button></li>
    {/each}
  </ul>
</section>
{#if failure}<p role="alert">{failure}</p>{/if}
{#if message}<p role="status">{message}</p>{/if}
<style>
  section { background: white; border-radius: .75rem; padding: 1rem; margin: 1rem 0; }
  label { display: flex; align-items: center; gap: .5rem; margin: .75rem 0; }
  input[type=checkbox] { width: auto; }
  input[type=time] { width: 8rem; }
  li { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; margin: 1rem 0; }
  li span { overflow-wrap: anywhere; flex: 1; min-width: 0; }
  ul { padding: 0; }
</style>
