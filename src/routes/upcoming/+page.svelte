<script lang="ts">
  import Today from '$lib/Today.svelte';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>
<svelte:head><title>Upcoming | Fytorium</title></svelte:head>
<a href="/">Home</a>
<Today list={data.tasks} sitter={data.identity.member.role === 'sitter'} memberId={data.identity.member.id} />
