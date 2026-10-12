import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { createFakePlanta, syntheticPlant } from './planta-fake';

const fake = createFakePlanta();
fake.env.HOUSEHOLD_TIMEZONE = 'America/Los_Angeles';
const harness = createApiHarness({ testClock: true, appEnv: fake.env });
let owner: Awaited<ReturnType<typeof harness.login>>;
beforeAll(async () => {
  owner = await harness.login();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-10T02:00:00Z' });
});

test('date-only care is a household calendar date, including the current household day', async () => {
  const plant = syntheticPlant('synthetic-local-date');
  plant.actions.watering.completed.date = '2026-06-09';
  fake.setPlants([plant]);
  expect((await owner.request('/api/import/planta', 'POST', { code: fake.code })).status).toBe(200);
  const committed = await owner.request('/api/import/planta/commit', 'POST', {});
  expect(committed.status).toBe(200);
  const row = (await committed.json())[0];
  const history = await (await owner.request('/api/plants/' + row.plant_id + '/history')).json();
  expect(history).toContainEqual(expect.objectContaining({ task_type: 'water', occurred_on: '2026-06-09', occurred_at: '2026-06-09T07:00:00.000Z' }));
  plant.actions.watering.completed.date = '2026-06-10';
  fake.setPlants([plant]);
  expect((await owner.request('/api/import/planta', 'POST', { code: fake.code })).status).toBe(422);
});
