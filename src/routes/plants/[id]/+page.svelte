<script lang="ts">
  import { plantLabels, plantBooleans, plantNumbers, plantChoices } from '$lib/plants';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
  const choices = Object.keys(plantChoices) as (keyof typeof plantChoices)[];
</script>
<svelte:head><title>{data.plant.display_name} | Fytorium</title></svelte:head>
<a href="/plants">Plants</a>
<h1>{data.plant.display_name}</h1>
<p><a href={'/plants/' + data.plant.id + '/settings'}>Plant settings</a></p>
<section>
  <h2>Species and site</h2>
  <p>{data.plant.scientific_name}</p>
  <p>{data.plant.canonical_name} · {data.plant.rank.toLowerCase()}</p>
  <p>GBIF key: <a href={'https://www.gbif.org/species/' + data.plant.gbif_key}>{data.plant.gbif_key}</a></p>
  <p>Species data from GBIF, CC BY 4.0</p>
  <p>Site: <a href={'/sites/' + data.plant.site_id}>{data.plant.site_name}</a></p>
  <dl><dt>Nickname</dt><dd>{data.plant.nickname ?? 'Not set'}</dd><dt>Cultivar</dt><dd>{data.plant.cultivar ?? 'Not set'}</dd><dt>Birthday</dt><dd>{data.plant.birthday ?? 'Not set'}</dd></dl>
</section>
<section>
  <h2>Pot and plant attributes</h2>
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
</style>
