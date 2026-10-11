import { expect, test } from '@playwright/test';

test('all external aliases serve synthetic placeholders', async ({ request }) => {
  for (const service of ['perenual', 'open-plantbook', 'plantnet', 'gbif', 'wikidata', 'nortaxa', 'open-meteo']) {
    const response = await request.get('http://' + service + ':8080/');
    expect(response.ok()).toBe(true);
    expect(await response.json()).toEqual({ synthetic: true, service, items: [] });
  }
});
