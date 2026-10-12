<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import { healthValues, healthLabel } from '$lib/photos';
  let { plantId, today }: { plantId: string; today: string } = $props();
  let busy = $state(false);
  let failure = $state('');
  let photo = $state<File | undefined>();
  function choosePhoto(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    if (input.files?.[0]) photo = input.files[0];
    input.value = '';
  }
  async function upload(event: SubmitEvent) {
    event.preventDefault();
    const element = event.currentTarget as HTMLFormElement;
    const form = new FormData(element);
    const file = photo;
    failure = '';
    if (!(file instanceof File) || !file.size) { failure = 'Choose an image.'; return; }
    if (file.size > 15 * 1024 * 1024) { failure = 'Photos must be at most 15 MB.'; return; }
    form.set('photo', file);
    busy = true;
    try {
      const response = await fetch('/api/plants/' + plantId + '/photos', { method: 'POST', body: form });
      if (!response.ok) { failure = (await response.json()).message ?? 'Could not upload the photo. Try again.'; return; }
      element.reset();
      photo = undefined;
      await invalidateAll();
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
<form onsubmit={upload}>
  <label for="photo-camera">Take photo</label>
  <input id="photo-camera" type="file" accept="image/*" capture="environment" onchange={choosePhoto} disabled={busy} />
  <label for="photo">Choose photo</label>
  <input id="photo" type="file" accept="image/*" onchange={choosePhoto} disabled={busy} />
  {#if photo}<p>Selected photo: {photo.name}</p>{/if}
  <p>Up to 15 MB. Location and camera metadata are removed.</p>
  <label for="photo-note">Note</label>
  <textarea id="photo-note" name="note" maxlength="5000" rows="3" disabled={busy}></textarea>
  <label for="photo-health">Photo health</label>
  <select id="photo-health" name="health" disabled={busy}>
    <option value="">Not set</option>
    {#each healthValues as health}<option value={health}>{healthLabel(health)}</option>{/each}
  </select>
  <label for="taken-on">Taken on</label>
  <input id="taken-on" name="taken_on" type="date" value={today} max={today} required disabled={busy} />
  {#if failure}<p class="error" role="alert">{failure}</p>{/if}
  <button disabled={busy}>{busy ? 'Uploading…' : 'Add photo update'}</button>
</form>
<style>
  label { display: block; margin: 1rem 0 .25rem; }
  input, select, textarea { box-sizing: border-box; width: 100%; min-width: 0; font: inherit; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; background: white; color: inherit; }
  input:focus-visible, select:focus-visible, textarea:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  button { margin-top: 1rem; }
  .error { color: #991b1b; }
</style>
