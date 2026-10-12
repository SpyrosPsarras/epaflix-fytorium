import { expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
const harness = createApiHarness();
test('plants_active_site CHECK maps to 422 with the site validation message', async () => {
  const { request } = await harness.login();
  const site = await (await request('/api/sites', 'POST', { name: 'Synthetic active site constraint', location: 'indoor', light: 'part_sun', grow_light: false, humidity: 'normal', draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const control = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await control.connect();
  try {
    await control.query(`CREATE FUNCTION synthetic_clear_plant_site() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN NEW.site_id = NULL; RETURN NEW; END;
      $$;
      CREATE TRIGGER synthetic_clear_plant_site BEFORE INSERT ON plants
        FOR EACH ROW EXECUTE FUNCTION synthetic_clear_plant_site();`);
    const response = await request('/api/plants', 'POST', { ...fixturePlant, nickname: 'Synthetic missing active site', site_id: site.id, search_id: search.search_id, gbif_key: 910001 });
    expect(response.status).toBe(422);
    expect((await response.json()).errors.site_id).toBe('Active plants must have a site.');
    expect(await (await request('/api/plants')).json()).toEqual([]);
  } finally {
    await control.query('DROP TRIGGER IF EXISTS synthetic_clear_plant_site ON plants; DROP FUNCTION IF EXISTS synthetic_clear_plant_site();');
    await control.end();
  }
});
test('a database CHECK failure maps to 422 with a clear validation message', async () => {
  const { request } = await harness.login();
  const site = await (await request('/api/sites', 'POST', { name: 'Synthetic constraint site', location: 'indoor', light: 'part_sun', grow_light: false, humidity: 'normal', draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const control = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await control.connect();
  try {
    await control.query("ALTER TABLE plants ADD CONSTRAINT synthetic_nickname_check CHECK (nickname <> 'Synthetic rejected plant')");
    const response = await request('/api/plants', 'POST', { ...fixturePlant, nickname: 'Synthetic rejected plant', site_id: site.id, search_id: search.search_id, gbif_key: 910001 });
    expect(response.status).toBe(422);
    expect((await response.json()).errors._form).toBe('Plant data does not meet required constraints.');
    expect(await (await request('/api/plants')).json()).toEqual([]);
  } finally {
    await control.query('ALTER TABLE plants DROP CONSTRAINT synthetic_nickname_check');
    await control.end();
  }
});
