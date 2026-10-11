<script lang="ts">
  import type { OutdoorMonths } from '$lib/weather';
  let { months }: { months: OutdoorMonths } = $props();
  const names = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const show = (value: number[] | null) => value === null ? 'Climate refresh pending' : value.length ? value.map(month => names[month - 1]).join(', ') : 'No suitable months';
</script>
<article aria-label="Outdoor months">
  <h3>Outdoor months</h3>
  {#if !months.locationSet}<p><a href="/settings">Set your location to see outdoor months</a></p>
  {:else}
    <dl><dt>Pot</dt><dd>{show(months.pot)}</dd><dt>Ground</dt><dd>{show(months.ground)}</dd></dl>
    <p>Ground hardiness: {months.groundThresholdC} °C. Potted hardiness: {months.potThresholdC} °C.</p>
    <p>Monthly mean minimum must be at least hardiness plus {months.marginC} °C. This estimate does not rule out frost.</p>
    <p>Hardiness source: {months.hardiness.source.replaceAll('_', ' ')} · {months.hardiness.value} °C{#if months.hardiness.ruleId} · {months.hardiness.ruleId}{/if}</p>
    {#if months.hardiness.ruleId === 'hardiness.usda_uncertain@1'}<p>USDA zone conversion is uncertain. Perenual does not name its hardiness scale.</p>{/if}
    <p>Rules: {months.potRuleId} · {months.ruleId}</p>
    <p>Climate from Open-Meteo, CMCC_CM2_VHR4 model, 1991 to 2020 daily minimum averages. Fetched: {months.fetchedAt ?? 'not fetched'}.</p>
    {#if months.unavailableSince}<p role="status">Climate unavailable since {months.unavailableSince}. Last good values remain.</p>{/if}
  {/if}
</article>
<style>
  article { border-top: 1px solid #d6d3d1; padding: .5rem 0; overflow-wrap: anywhere; }
  dt { font-weight: 600; }
  dd { margin: .25rem 0 1rem; }
</style>
