import { expect, test } from '@playwright/test';
import { fixturePlant } from '../plant-fixture';
import { logIn } from './helpers/login';

test('Plantbook care and Based on show provenance, while unmatched species can be retried on a phone', async ({ page }, testInfo) => {
  test.setTimeout(120000);
  const key = 100000000 + Math.floor(Math.random() * 700000000);
  await page.request.post('/__test/clock', { data: { action: 'set', at: '2030-06-01T12:00:00Z' } });
  await logIn(page, 'synthetic-browser-owner');
  const screenshot = async (file: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(file), fullPage: true });
  };
  try {
    const site = await (await page.request.post('/api/sites', { data: { name: 'Test sensor room ' + key, location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false } })).json();
    async function add(name: string, gbifKey: number) {
      const search = await (await page.request.get('/api/species/search?q=' + encodeURIComponent(name))).json();
      const response = await page.request.post('/api/plants', { data: { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: gbifKey } });
      expect(response.status()).toBe(201);
      return response.json();
    }
    const plant = await add('Testoria sensor ' + key, key);
    await page.goto('/species/' + key);
    const humidity = page.getByRole('article', { name: 'Humidity need', exact: true });
    await expect(humidity).toContainText('Source: open_plantbook', { timeout: 60000 });
    await expect(humidity.locator('.value')).toHaveText('low');
    await expect(humidity).toContainText('Fetch time: 2030-06-01T12:00:00.000Z');
    await screenshot('open-plantbook-care.png');
    await page.goto('/plants/' + plant.id);
    await expect(page.getByText('Mist is not applicable.', { exact: true })).toBeVisible();
    const basis = page.getByRole('region', { name: 'Based on', exact: true });
    await basis.getByText('Mist', { exact: true }).click();
    await expect(basis).toContainText('Source: open_plantbook');
    await expect(basis).toContainText('humidityNeed');
    await screenshot('open-plantbook-based-on.png');
    await add('Testoria unmatched ' + (key + 1), key + 1);
    await page.goto('/species/' + (key + 1));
    await expect(page.getByText('Open Plantbook: no match', { exact: true })).toBeVisible({ timeout: 60000 });
    await screenshot('open-plantbook-no-match.png');
    const retried = page.waitForResponse(response => response.url().endsWith('/care/retry-match') && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Retry match', exact: true }).click();
    expect((await retried).status()).toBe(202);
    await expect.poll(async () => (await (await page.request.get('/api/species/unmatched')).json()).map((item: { gbif_key: number }) => item.gbif_key), { timeout: 60000 }).toContain(key + 1);
    await expect(page.getByText('Match retry queued.', { exact: true })).toBeVisible();
    await expect(page.getByText('Open Plantbook: no match', { exact: true })).toBeVisible({ timeout: 60000 });
    await page.goto('/settings');
    await page.getByRole('link', { name: 'Open Plantbook unmatched species', exact: true }).click();
    await expect(page.getByRole('link', { name: 'Testoria unmatched ' + (key + 1), exact: true })).toBeVisible();
    await screenshot('open-plantbook-unmatched-list.png');
  } finally { await page.request.post('/__test/clock', { data: { action: 'reset' } }); }
});
