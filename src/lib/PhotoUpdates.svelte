<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import { healthLabel, type PhotoUpdate } from '$lib/photos';
  let { updates }: { updates: PhotoUpdate[] } = $props();
  let busy = $state(false);
  let failure = $state('');
  async function change(photo: PhotoUpdate, action: 'cover' | 'delete') {
    if (action === 'delete' && !confirm('Delete this photo update and its photos?')) return;
    busy = true;
    failure = '';
    try {
      const response = await fetch('/api/photos/' + photo.id + (action === 'cover' ? '/cover' : ''), { method: action === 'cover' ? 'PUT' : 'DELETE' });
      if (!response.ok) { failure = (await response.json()).message ?? 'Could not change the photo update.'; return; }
      await invalidateAll();
    } catch { failure = 'Could not reach Fytorium. Try again.'; }
    finally { busy = false; }
  }
</script>
{#if failure}<p class="error" role="alert">{failure}</p>{/if}
{#if updates.length === 0}<p>No photo updates yet.</p>{/if}
{#each updates as photo (photo.id)}
  <article>
    <h3><a href={'/plants/' + photo.plant_id}>{photo.plant_name}</a></h3>
    <a href={'/api/photos/' + photo.id + '/original'} aria-label={'Open original photo of ' + photo.plant_name}>
      <img src={'/api/photos/' + photo.id + '/thumb'} alt={'Photo update of ' + photo.plant_name} loading="lazy" />
    </a>
    <p><time datetime={photo.taken_on}>{photo.taken_on}</time>{#if photo.is_cover}{' · '}<span>Cover photo</span>{/if}</p>
    {#if photo.health}<p>Health: {healthLabel(photo.health)}</p>{/if}
    {#if photo.note}<p class="note">{photo.note}</p>{/if}
    <div class="controls">
      {#if !photo.is_cover}<button disabled={busy} onclick={() => change(photo, 'cover')}>Use as cover</button>{/if}
      <button disabled={busy} onclick={() => change(photo, 'delete')}>Delete photo update</button>
    </div>
  </article>
{/each}
<style>
  article { background: white; border: 1px solid #e7e5e4; border-radius: .75rem; padding: 1rem; margin-top: 1rem; overflow-wrap: anywhere; }
  h3 { margin-top: 0; }
  img { display: block; width: 100%; max-height: 25rem; object-fit: contain; border-radius: .5rem; background: #ffedd5; }
  .note { white-space: pre-wrap; }
  .controls { display: flex; flex-wrap: wrap; gap: .5rem; }
  .error { color: #991b1b; }
</style>
