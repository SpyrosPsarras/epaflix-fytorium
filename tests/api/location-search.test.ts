import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';

// W5a SV2: the owner finds the household location by town name; the phone talks only to Fytorium.
let status = 200;
let delay = 0;
let body: unknown = { results: [
  { name: 'Bergen', admin1: 'Vestland', country: 'Norway', latitude: 60.39299, longitude: 5.32415 },
  { name: 'Bergen', admin1: 'Bergen', country: 'Netherlands', latitude: 52.66917, longitude: 4.70417 },
] };
const searches: URL[] = [];
const harness = createApiHarness({ weather: url => {
  if (url.pathname !== '/v1/search') return { status: 404, body: {} };
  searches.push(url);
  return { status, body, delay };
} });
let owner: Awaited<ReturnType<typeof harness.login>>;
beforeAll(async () => { owner = await harness.login(); });
const search = (q: string, who = owner) => who.request('/api/household/location/search?q=' + encodeURIComponent(q));

test('owner gets labelled places in the member language, and only the query reaches Open-Meteo', async () => {
  const response = await search('  Bergen ');
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ results: [
    { label: 'Bergen, Vestland, Norway', name: 'Bergen', region: 'Vestland', country: 'Norway', latitude: 60.39299, longitude: 5.32415 },
    { label: 'Bergen, Netherlands', name: 'Bergen', region: 'Bergen', country: 'Netherlands', latitude: 52.66917, longitude: 4.70417 },
  ] });
  const sent = searches.at(-1)!;
  expect(Object.fromEntries(sent.searchParams)).toEqual({ name: 'Bergen', count: '10', language: 'en', format: 'json' });
});

test('no match is an empty list; a too short or too long query is refused without a call', async () => {
  body = { generationtime_ms: 0.1 };
  expect(await (await search('Zzqx')).json()).toEqual({ results: [] });
  const calls = searches.length;
  for (const q of ['B', 'B'.repeat(101)]) {
    const response = await search(q);
    expect(response.status).toBe(422);
    expect(await response.json()).toEqual({ errors: { q: 'Type 2 to 100 letters.' } });
  }
  expect(searches.length).toBe(calls);
});

test('an upstream failure, a malformed answer or a timeout says place search is not responding', async () => {
  for (const [nextStatus, nextBody, nextDelay] of [[500, {}, 0], [200, { results: 'bad' }, 0], [200, { results: [] }, 9000]] as const) {
    status = nextStatus; body = nextBody; delay = nextDelay;
    const response = await search('Bergen');
    expect(response.status).toBe(503);
    expect((await response.json()).message).toBe("Place search isn't responding. Try again in a minute.");
  }
  status = 200; delay = 0;
}, 30000);

test('family and sitters cannot search', async () => {
  for (const role of ['family', 'sitter'] as const) {
    const invite = await (await owner.request('/api/household/invites', 'POST', role === 'sitter' ? { role, plant_ids: [] } : { role })).json();
    const member = await harness.login('synthetic-search-' + role, { name: 'Synthetic ' + role }, invite.link.split('/').at(-1));
    expect((await search('Bergen', member)).status).toBe(403);
  }
});
