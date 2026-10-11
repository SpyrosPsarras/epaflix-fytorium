import { expect, type APIRequestContext } from '@playwright/test';
import { fixturePlant } from '../../plant-fixture';

export async function addPlantForSite(request: APIRequestContext, siteName: string, nickname: string) {
  const sites = await (await request.get('/api/sites')).json();
  const site = sites.find((item: { name: string }) => item.name === siteName);
  expect(site).toBeDefined();
  const search = await request.get('/api/species/search?q=Testoria%20lucida');
  expect(search.status()).toBe(200);
  const result = await search.json();
  const added = await request.post('/api/plants', { data: { ...fixturePlant, site_id: site.id, nickname, search_id: result.search_id, gbif_key: 910001 } });
  expect(added.status()).toBe(201);
}
