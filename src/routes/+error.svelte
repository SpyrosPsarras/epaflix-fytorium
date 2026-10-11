<script lang="ts">
  import { page } from '$app/state';
</script>
<svelte:head><title>Fytorium | {page.status}</title></svelte:head>
<h1>{page.status === 403 ? 'Not a member' : 'Something went wrong'}</h1>
<p>{page.error?.message}</p>
<a href="/auth/login">Try logging in again</a>
