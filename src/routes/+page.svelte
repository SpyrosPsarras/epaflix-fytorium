<script lang="ts">
  import HomeFeed from '$lib/HomeFeed.svelte';
  import Today from '$lib/Today.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Fytorium</title></svelte:head>
<header><img src="/icon.svg" width="48" height="48" alt="" /><h1>Fytorium</h1></header>
  <p>{data.identity.household.name} · {data.identity.member.role}</p>
  {#if data.identity.member.role !== 'sitter'}
  <nav><a href="/plants">Plants</a> · <a href="/sites">Sites</a> · <a href="/journal">Journal</a>{#if data.identity.member.role === 'owner'} · <a href="/settings">Household settings</a>{/if}{#if data.identity.member.role === 'owner'} · <a href="/import">Import from Planta</a>{/if}</nav>
  <section><h2>{data.plantCount === 0 ? 'No plants yet' : data.plantCount + (data.plantCount === 1 ? ' plant' : ' plants')}</h2><p><a href="/plants">Open your plant collection</a></p></section>
  {/if}
  <p><a href="/settings/notifications">My notifications</a>{#if data.identity.member.role === 'owner'} · <a href="/members">Members</a>{/if}</p>
  {#if data.feed}
    <HomeFeed cards={data.feed.cards} weather={'weather' in data.feed ? data.feed.weather : undefined} sitter={data.identity.member.role === 'sitter'} />
  {/if}
  <div id="today">{#if data.plantCount > 0 && data.tasks}<Today list={data.tasks} sitter={data.identity.member.role === 'sitter'} memberId={data.identity.member.id} />{/if}</div>
  <form method="POST" action="/auth/logout"><button>Log out</button></form>
<style>
  header { display: flex; gap: .75rem; align-items: center; }
  section { padding: 2rem 1rem; margin: 2rem 0; background: white; border-radius: .75rem; text-align: center; }
</style>
