import { beforeAll, expect, test } from 'vitest';
import { fixturePlant } from '../plant-fixture';
import { createApiHarness } from './harness';

let survivalC: number | undefined = -5;
let zones: { min: number; max: number } | undefined = { min: 12, max: 13 };
const harness = createApiHarness({
  testClock: true,
  careEnv: { PERENUAL_PER_MINUTE: '200', OPEN_PLANTBOOK_PER_MINUTE: '200' },
  perenual: path => ({ status: 200, body: path.endsWith('species-list')
    ? { data: [{ id: 2100, scientific_name: ['Testoria lucida'] }] }
    : { hardiness: zones } }),
  openPlantbook: url => ({ status: 200, body: url.pathname.endsWith('/search')
    ? { results: [{ pid: 'testoria_lucida', display_pid: 'Testoria lucida' }] }
    : { min_temp_survival: survivalC } }),
  weather: url => ({ status: 200, body: url.pathname === '/v1/forecast'
    ? { daily: { time: ['2030-06-01'], temperature_2m_min: [-1], temperature_2m_max: [20], precipitation_sum: [0], daylight_duration: [43200] } }
    : { daily: { time: ['2000-01-01', '2000-02-01', '2000-03-01', '2000-04-01', '2000-05-01', '2000-06-01', '2000-07-01', '2000-08-01', '2000-09-01', '2000-10-01', '2000-11-01', '2000-12-01'], temperature_2m_min: [-5, -3, 2, 6, 10, 15, 18, 17, 12, 7, 1, -4] } } }),
});
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let pot: string;
let ground: string;
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  ({ request } = await harness.login());
  const site = await (await request('/api/sites', 'POST', { name: 'Test hardiness garden', location: 'outdoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const add = async (pot_type: 'plastic' | 'in_ground') => {
    const response = await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, pot_type });
    expect(response.status).toBe(201);
    return (await response.json()).id as string;
  };
  pot = await add('plastic');
  ground = await add('in_ground');
  await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
});

test('Open Plantbook Celsius wins over Perenual zones for cold alerts and outdoor months', async () => {
  expect((await request('/__test/care-worker', 'POST')).status).toBe(200);
  await request('/__test/weather-worker', 'POST');
  const care = await (await request('/api/species/910001/care')).json();
  expect(care.profile.hardinessC).toMatchObject({ value: -5, source: 'open_plantbook' });
  expect(care.profile.hardiness).toMatchObject({ value: { min: 12, max: 13 }, source: 'perenual' });
  const report = await (await request('/api/weather')).json();
  expect(report.alerts).toHaveLength(1);
  expect(report.alerts[0]).toMatchObject({ plant_id: pot, payload: { thresholdC: 0, minC: -1, hardiness: { value: -5, source: 'open_plantbook', fetchedAt: '2030-06-01T12:00:00.000Z' } } });
  expect(report.alerts[0].payload.hardiness.ruleId).toBeUndefined();
  const speciesPage = (await (await request('/species/910001')).text()).replace(/<!--[\s\S]*?-->/g, '');
  expect(speciesPage).toContain('Hardiness source: open plantbook · -5 °C');
  expect(speciesPage).not.toContain('USDA zone conversion is uncertain');
  const plantPage = (await (await request('/plants/' + pot)).text()).replace(/<!--[\s\S]*?-->/g, '');
  expect(plantPage.split('id="based-on-title"')[1]).toContain('Hardiness source: open plantbook · -5 °C');
  expect(await (await request('/api/species/910001/outdoor-months')).json()).toMatchObject({ groundThresholdC: -5, potThresholdC: 0, ground: [2, 3, 4, 5, 6, 7, 8, 9, 10, 11], pot: [3, 4, 5, 6, 7, 8, 9, 10], hardiness: { value: -5, source: 'open_plantbook' } });
});

test('zero Celsius override is present and beats even an overridden zone range', async () => {
  await request('/api/species/910001/care/overrides/hardinessC', 'PUT', { value: 0 });
  await request('/api/species/910001/care/overrides/hardiness', 'PUT', { value: { min: 13, max: 13 } });
  await request('/__test/weather-worker', 'POST');
  const alerts = (await (await request('/api/weather')).json()).alerts;
  expect(alerts).toHaveLength(2);
  expect(alerts.find((alert: { plant_id: string }) => alert.plant_id === pot)).toMatchObject({ payload: { thresholdC: 5, hardiness: { value: 0, source: 'override' } } });
  expect(alerts.find((alert: { plant_id: string }) => alert.plant_id === ground)).toMatchObject({ payload: { thresholdC: 0, hardiness: { value: 0, source: 'override' } } });
  expect(await (await request('/api/species/910001/outdoor-months')).json()).toMatchObject({ groundThresholdC: 0, potThresholdC: 5, ground: [3, 4, 5, 6, 7, 8, 9, 10], pot: [5, 6, 7, 8, 9, 10], hardiness: { value: 0, source: 'override' } });
  await request('/api/species/910001/care/overrides/hardinessC', 'DELETE');
  await request('/api/species/910001/care/overrides/hardiness', 'DELETE');
});

test.each([
  { label: 'uncertain Perenual zone mapping', zones: { min: 12, max: 13 }, value: 10, potThresholdC: 15, source: 'perenual', ruleId: 'hardiness.usda_uncertain@1', groundMonths: [6, 7, 8, 9], potMonths: [7, 8] },
  { label: 'rule default without either source', zones: undefined, value: 5, potThresholdC: 10, source: 'rule', ruleId: 'hardiness.default@1', groundMonths: [5, 6, 7, 8, 9, 10], potMonths: [6, 7, 8, 9] },
])('alerts and outdoor months fall back to $label', async row => {
  survivalC = undefined;
  zones = row.zones;
  expect((await request('/api/species/910001/care/refresh', 'POST')).status).toBe(202);
  await request('/__test/care-worker', 'POST');
  await request('/__test/weather-worker', 'POST');
  const hardiness = { value: row.value, source: row.source, ruleId: row.ruleId };
  const alerts = (await (await request('/api/weather')).json()).alerts;
  expect(alerts).toHaveLength(2);
  expect(alerts.find((alert: { plant_id: string }) => alert.plant_id === pot)).toMatchObject({ payload: { thresholdC: row.potThresholdC, hardiness } });
  expect(alerts.find((alert: { plant_id: string }) => alert.plant_id === ground)).toMatchObject({ payload: { thresholdC: row.value, hardiness } });
  expect(await (await request('/api/species/910001/outdoor-months')).json()).toMatchObject({ groundThresholdC: row.value, potThresholdC: row.potThresholdC, ground: row.groundMonths, pot: row.potMonths, hardiness });
  const page = await (await request('/species/910001')).text();
  expect(page.includes('USDA zone conversion is uncertain')).toBe(row.source === 'perenual');
});
