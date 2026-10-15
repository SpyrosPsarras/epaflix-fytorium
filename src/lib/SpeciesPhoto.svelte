<script lang="ts">
  import Thumbnail from './Thumbnail.svelte';
  let { gbifKey, name, showName = false, displayPhoto = true, size = '10rem' }: { gbifKey: number; name: string; showName?: boolean; displayPhoto?: boolean; size?: string } = $props();
  let commonName = $state<string | null>(null);
  let credit = $state<{ creator: string; license: string; source_url: string } | null>(null);
  $effect(() => {
    const controller = new AbortController();
    credit = null; commonName = null;
    fetch(`/api/species/${gbifKey}/photo/credit`, { signal: controller.signal })
      .then(async response => {
        if (!response.ok) return;
        const body = await response.json();
        if (!controller.signal.aborted) { credit = body.photo; commonName = body.common_name; }
      })
      .catch(() => {});
    return () => controller.abort();
  });
</script>
<figure style:width={size}>
  {#if displayPhoto}<Thumbnail src={credit ? `/api/species/${gbifKey}/photo` : null} alt={'Reference photo of ' + name} placeholder="No reference photo" {size} />{/if}
  {#if showName}<p class="species-name"><strong>{commonName ?? name}</strong><small>{commonName ? name : ''}</small></p>{/if}
  {#if displayPhoto}<figcaption>{#if credit}<a href={credit.source_url} target="_blank" rel="noreferrer" title={`${credit.license} · Photo: ${credit.creator}`}>{credit.license} · Photo: {credit.creator}</a>{/if}</figcaption>{/if}
</figure>
<style>
  figure { margin: 0 0 .5rem; max-width: 100%; }
  .species-name { margin: .5rem 0; overflow-wrap: anywhere; }
  .species-name strong { display: block; min-height: 2lh; }
  .species-name small { display: block; min-height: 1lh; }
  figcaption a { position: relative; z-index: 2; display: block; }
  figcaption { min-height: 3lh; font-size: .875rem; overflow-wrap: anywhere; }
</style>
