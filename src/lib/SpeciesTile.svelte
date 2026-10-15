<script lang="ts">
  import SpeciesPhoto from './SpeciesPhoto.svelte';
  import type { Candidate } from './plants';
  let { candidate, suggested = false, disabled = false, onclick }: { candidate: Candidate; suggested?: boolean; disabled?: boolean; onclick: () => void } = $props();
</script>
<div class="tile" class:suggested>
  <SpeciesPhoto gbifKey={candidate.gbif_key} name={candidate.canonical_name} showName size="100%" />
  <p class="choose">This is my plant</p>
  {#if suggested}<p>Looks most like your photo</p>{/if}
  <button {disabled} {onclick} aria-label={'This is ' + candidate.canonical_name}></button>
</div>
<style>
  .tile { min-width: 0; position: relative; border: 2px solid #d6d3d1; border-radius: .75rem; padding: .75rem; min-height: 48px; }
  .suggested { border-color: #15803d; }
  p { color: #166534; font-weight: bold; margin-bottom: 0; }
  .choose { display: inline-block; padding: .75rem 1rem; border-radius: .5rem; border: 1px solid #9a3412; background: white; color: #9a3412; font-weight: normal; }
  button { position: absolute; inset: 0; width: 100%; border: 0; background: transparent; border-radius: .5rem; cursor: pointer; }
  button:hover { background: #1665340a; }
  button:focus-visible { outline: 3px solid #ea580c; outline-offset: 2px; }
  button:disabled { cursor: default; }
  .tile:has(button:disabled) .choose { opacity: .5; }
</style>
