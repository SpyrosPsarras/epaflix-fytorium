<script lang="ts">
  import { invalidateAll } from '$app/navigation';
  import type { FeedCard } from '$lib/feed';
  import type { WeatherReport } from '$lib/weather';
  import WeatherBanner from '$lib/WeatherBanner.svelte';
  import type { PlantWarning } from '$lib/warnings';
  let { cards, weather, sitter = false }: { cards: FeedCard[]; weather?: WeatherReport; sitter?: boolean } = $props();
  let busy = $state(false);
  const rainCardId = $derived([...cards].reverse().find(card => card.kind === 'weather_alerts' && card.event.kind === 'rain')?.id);
  const labels: Record<PlantWarning['kind'], string> = { too_dark: 'Too dark', too_bright: 'Too bright', no_drainage: 'No drainage', missing_info: 'Missing information' };
  $effect(() => {
    if (!weather?.locationSet) return;
    const timer = setInterval(() => { if (!busy) void invalidateAll(); }, weather.forecastFetchedAt ? 30000 : 3000);
    return () => clearInterval(timer);
  });
</script>
<section aria-labelledby="home-feed-title">
  <div class="heading"><h2 id="home-feed-title">Home feed</h2>{#if !sitter}<a href="/settings/notifications#feed-settings">Customize my feed</a>{/if}</div>
  <div class="feed-cards">
  <div class="cards">
    {#each cards as card (card.id)}
      {#if card.kind === 'late_tasks'}
        <article aria-label="Late tasks"><h3><a href={card.href}>{card.count} {card.count === 1 ? 'task is' : 'tasks are'} late</a></h3>
          <p>{#each card.plants as plant, i}{#if i > 0}, {/if}<a href={'/plants/' + plant.id}>{plant.name}</a>{/each}</p>
          {#if card.href === '/#today'}<p><a href={card.href}>See all in Today</a></p>{/if}
        </article>
      {:else if card.kind === 'current_weather'}
        <article aria-label="Current weather"><h3><a href={card.href}>Current weather</a></h3>
          {#if card.unavailableSince}<p role="status">Weather unavailable since {card.unavailableSince}. Showing last good data where available.</p>
          {:else if !card.current}<p role="status">Weather is waiting for its background refresh.</p>{/if}
          {#if card.current}
            <p>Today: low {card.current.minC} °C, high {card.current.maxC} °C.</p>
            <p>Open-Meteo · Fetched {card.current.fetchedAt}</p>
          {/if}
        </article>
      {/if}
    {/each}
  </div>
  {#if cards.some(card => card.kind === 'weather_alerts')}
    <ul class="cards" aria-label="Weather alerts">
      {#each cards as card (card.id)}
        {#if card.kind === 'weather_alerts'}
          <li><article aria-label={card.event.kind === 'rain' ? card.id === rainCardId ? 'Rain report' : 'Rain report for ' + card.event.starts_on : undefined}>
            {#if card.event.kind === 'low_temperature'}
            <h3><a href={card.href}>{card.event.plant_name ?? 'Low temperature alert'}</a></h3>
              <p>Low temperature {card.event.payload.minC} °C, below {card.event.payload.thresholdC} °C hardiness, {card.event.starts_on} to {card.event.ends_on}. Bring it in or protect it.</p>
              <p>Hardiness source: {card.event.payload.hardiness?.source.replaceAll('_', ' ')} · {card.event.payload.hardiness?.value} °C{#if card.event.payload.hardiness?.ruleId} · {card.event.payload.hardiness.ruleId}{/if}{#if card.event.payload.potRuleId} · {card.event.payload.potRuleId}{/if}</p>
              {#if card.event.payload.hardiness?.ruleId === 'hardiness.usda_uncertain@1'}<p>USDA zone conversion is uncertain.</p>{/if}
            {:else}
              <a class="card-body" href={card.href}><h3>Rain report</h3>{#if card.event.payload.precipitationMm != null}<p>Rain: {card.event.payload.precipitationMm} mm on {card.event.starts_on}.</p>{/if}</a>
              {#if card.id === rainCardId && weather}<WeatherBanner report={weather} showRain rainOnly bind:busy />{/if}
            {/if}
          </article></li>
        {/if}
      {/each}
    </ul>
  {/if}
  <div class="cards">
    {#each cards as card (card.id)}
      {#if card.kind === 'plant_warnings'}
        <article aria-label={'Warnings for ' + card.plantName}>
          <h3><a href={card.href}>{card.plantName}</a></h3>
          {#each card.warnings as warning}
            <h4>{labels[warning.kind]}</h4><p>{warning.reason}</p>
            <details><summary>Warning inputs</summary><ul>
              {#each warning.inputs as basis}<li>{basis.input}: {typeof basis.value === 'object' ? JSON.stringify(basis.value) : String(basis.value)} · {basis.source}{#if basis.ruleId} · {basis.ruleId}{/if}{#if basis.fetchedAt} · Fetched {basis.fetchedAt}{/if}</li>{/each}
            </ul></details>
          {/each}
        </article>
      {/if}
    {/each}
  </div>
  </div>
</section>
<style>
  section { margin: 1.5rem 0; }
  .heading { display: flex; flex-wrap: wrap; gap: .5rem 1rem; align-items: center; justify-content: space-between; }
  .feed-cards { display: flex; gap: .75rem; overflow-x: auto; scroll-snap-type: x proximity; padding: .75rem 0; align-items: flex-start; }
  .cards { display: contents; padding: 0; list-style: none; }
  article { background: white; border-radius: .75rem; padding: 1rem; overflow-wrap: anywhere; box-sizing: border-box; width: clamp(260px, 80vw, 340px); flex-shrink: 0; scroll-snap-align: start; }
  h3, h4 { margin: 0 0 .5rem; }
  h4 { margin-top: .75rem; }
  a, summary { display: inline-block; min-height: 44px; align-content: center; }
  .card-body { display: block; }
  details ul { padding-left: 1.25rem; }
  p { margin: .5rem 0; }
</style>
