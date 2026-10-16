<script lang="ts">
  let { src, alt, placeholder, size = '10rem' }: { src: string | null; alt: string; placeholder: string; size?: string } = $props();
  let failed = $state(false);
  $effect(() => { src; failed = false; });
</script>
{#if src && !failed}
  <img {src} {alt} width="160" height="160" style:width={size} onerror={() => failed = true} />
{:else}
  <div class="placeholder" style:width={size} role="img" aria-label={placeholder}>{placeholder}</div>
{/if}
<style>
  img, .placeholder { width: 10rem; height: auto; aspect-ratio: 1; max-width: 100%; border-radius: .5rem; }
  img { display: block; object-fit: cover; }
  .placeholder { display: flex; align-items: center; justify-content: center; background: #f5f5f4; color: #57534e; text-align: center; }
</style>
