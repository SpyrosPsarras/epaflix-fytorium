<script lang="ts">
  import Today from '$lib/Today.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Fytorium</title></svelte:head>
<header><img src="/icon.svg" width="48" height="48" alt="" /><h1>Fytorium</h1></header>
{#if data.identity}
  <p>{data.identity.household.name} · {data.identity.member.role}</p>
  <nav><a href="/plants">Plants</a> · <a href="/sites">Sites</a></nav>
  <section><h2>{data.plantCount === 0 ? 'No plants yet' : data.plantCount + (data.plantCount === 1 ? ' plant' : ' plants')}</h2><p><a href="/plants">Open your plant collection</a></p></section>
  {#if data.plantCount > 0 && data.tasks}<Today list={data.tasks} />{/if}
  <form method="POST" action="/auth/logout"><button>Log out</button></form>
{:else}
  <p>Plant care for your household.</p>
  <a class="login" href="/auth/login">Log in</a>
{/if}
<style>
  header { display: flex; gap: .75rem; align-items: center; }
  section { padding: 2rem 1rem; margin: 2rem 0; background: white; border-radius: .75rem; text-align: center; }
  .login { display: inline-block; padding: .75rem 1.5rem; border-radius: .5rem; background: #9a3412; color: white; text-decoration: none; }
</style>
