<script lang="ts">
  import type { Plant } from '$lib/plants';
  let { plants }: { plants: Plant[] } = $props();
  let query = $state('');
  const filtered = $derived(plants.filter(plant => (plant.display_name + ' ' + plant.scientific_name).toLowerCase().includes(query.trim().toLowerCase())));
</script>

<label for="plant-search">Search plants</label>
<input id="plant-search" type="search" bind:value={query} placeholder="Name or scientific name" />
{#if filtered.length === 0}<p>No plants found.</p>{/if}
{#each filtered as plant (plant.id)}
  <article>
    {#if plant.cover_photo_id}
      <img class="photo" src={'/api/photos/' + plant.cover_photo_id + '/thumb'} alt={'Cover photo of ' + plant.display_name} loading="lazy" />
    {:else}<div class="photo" role="img" aria-label="No plant photo yet">No photo</div>{/if}
    <div><h2><a href={'/plants/' + plant.id}>{plant.display_name}</a></h2><p>{plant.scientific_name}</p><a href={'/sites/' + plant.site_id}>{plant.site_name}</a></div>
  </article>
{/each}
<style>
  label { display: block; margin-top: 1rem; }
  input { box-sizing: border-box; width: 100%; font: inherit; padding: .75rem; border: 1px solid #78716c; border-radius: .5rem; }
  input:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  article { display: flex; gap: 1rem; background: white; border-radius: .75rem; margin-top: 1rem; padding: 1rem; overflow-wrap: anywhere; }
  article > div:last-child { min-width: 0; }
  .photo { flex-shrink: 0; width: 4.5rem; height: 5rem; display: grid; place-items: center; border-radius: .5rem; background: #ffedd5; color: #57534e; font-size: .8rem; }
  img.photo { object-fit: cover; }
  h2 { font-size: 1.1rem; margin: 0; }
  p { margin: .25rem 0; }
</style>
