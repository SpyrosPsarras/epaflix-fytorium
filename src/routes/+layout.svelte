<script lang="ts">
  let { children } = $props();
</script>
<main>{@render children()}</main>
<style>
  :global(body) { margin: 0; background: #fff7ed; color: #292524; font-family: system-ui, sans-serif; line-height: 1.5; }
  main { max-width: 42rem; margin: auto; padding: 1.5rem; }
  :global(a) { color: #9a3412; }
  :global(a:focus-visible), :global(button:focus-visible) { outline: 3px solid #ea580c; outline-offset: 4px; }
  :global(button) { font: inherit; padding: .75rem 1rem; cursor: pointer; border-radius: .5rem; border: 1px solid #9a3412; background: white; color: #9a3412; }
</style>
