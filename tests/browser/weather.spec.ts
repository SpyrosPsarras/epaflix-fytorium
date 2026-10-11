import { expect, test } from '@playwright/test';
import { logIn } from './helpers/login';
import { fixturePlant } from '../plant-fixture';

test('household weather shows cold alerts, waters outdoor plants as one action and shows outdoor months on a phone', async ({ page }, testInfo) => {
  test.setTimeout(90000);
  const key = 100000000 + Math.floor(Math.random() * 800000000);
  const name = 'Testoria weather ' + key;
  const plants: string[] = [];
  const sites: string[] = [];
  const screenshot = async (file: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(file), fullPage: true });
  };
  await page.request.post('/__test/clock', { data: { action: 'set', at: '2030-06-01T12:00:00Z' } });
  await logIn(page, 'synthetic-browser-owner');
  try {
    await page.request.put('/api/household/location', { data: { location: null } });
    for (const location of ['outdoor', 'indoor']) {
      const site = await (await page.request.post('/api/sites', { data: { name: 'Test weather ' + location + ' ' + key, location, light: 'part_sun', humidity: 'normal', grow_light: false, draft: false } })).json();
      sites.push(site.id);
      const search = await (await page.request.get('/api/species/search?q=' + encodeURIComponent(name))).json();
      const response = await page.request.post('/api/plants', { data: { ...fixturePlant, nickname: 'Test weather ' + location + ' ' + key, site_id: site.id, search_id: search.search_id, gbif_key: key } });
      expect(response.status()).toBe(201);
      plants.push((await response.json()).id);
    }
    await page.goto('/species/' + key);
    await expect(page.getByText('Set your location to see outdoor months', { exact: true })).toBeVisible();
    await page.goto('/settings');
    await page.getByLabel('Latitude', { exact: true }).fill('0.5');
    await page.getByLabel('Longitude', { exact: true }).fill('0.5');
    await page.getByLabel('Place label, optional', { exact: true }).fill('Test weather location');
    await page.getByRole('button', { name: 'Save location', exact: true }).click();
    await expect(page.getByText('Location saved. Weather refreshes in the background.', { exact: true })).toBeVisible();
    await screenshot('weather-location.png');
    await page.goto('/');
    const alerts = page.getByRole('list', { name: 'Weather alerts', exact: true });
    await expect(alerts).toContainText('Test weather outdoor ' + key, { timeout: 25000 });
    await expect(alerts).not.toContainText('Test weather indoor ' + key);
    await screenshot('weather-cold-alert.png');
    // The household may contain outdoor plants from earlier scenarios. The batch covers all of them.
    const report = await (await page.request.get('/api/weather')).json();
    const rainCard = page.getByRole('article', { name: 'Rain report', exact: true });
    await expect(rainCard.getByRole('link', { name: /Rain report/ })).toHaveAttribute('href', '/settings#weather-location-title');
    await rainCard.getByRole('button', { name: `Mark ${report.rain.plantCount} outdoor plants watered`, exact: true }).click();
    await expect(page.getByText(`Marked ${report.rain.plantCount} outdoor plants watered.`, { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: `Mark ${report.rain.plantCount} outdoor plants watered`, exact: true })).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('button', { name: `Mark ${report.rain.plantCount} outdoor plants watered`, exact: true })).toHaveCount(0);
    const outdoorHistory = await (await page.request.get('/api/plants/' + plants[0] + '/history')).json();
    expect(outdoorHistory).toContainEqual(expect.objectContaining({ task_type: 'water', batch_id: expect.any(String), undone_at: null }));
    expect(await (await page.request.get('/api/plants/' + plants[1] + '/history')).json()).toEqual([]);
    await screenshot('weather-rain-watered.png');
    await rainCard.getByRole('button', { name: 'Undo rain watering', exact: true }).click();
    await expect(page.getByText('Rain watering undone.', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: `Mark ${report.rain.plantCount} outdoor plants watered`, exact: true })).toBeVisible();
    await rainCard.getByRole('link', { name: /Rain report/ }).tap();
    await expect(page).toHaveURL('/settings#weather-location-title');
    await page.goto('/plants/' + plants[0]);
    await expect(page.getByRole('list', { name: 'Weather alerts', exact: true })).toContainText('Test weather outdoor ' + key);
    await page.goto('/species/' + key);
    const months = page.getByRole('article', { name: 'Outdoor months', exact: true });
    await expect(months).toContainText('Pot');
    await expect(months).toContainText('Ground');
    await expect(months).toContainText('June, July, August, September', { timeout: 25000 });
    await expect(months).toContainText('May, June, July, August, September, October');
    await screenshot('weather-outdoor-months.png');
    expect((await page.request.put('/api/species/' + key + '/care/overrides/hardiness', { data: { value: { min: 12, max: 13 } } })).status()).toBe(200);
    expect((await page.request.put('/api/species/' + key + '/care/overrides/hardinessC', { data: { value: 6 } })).status()).toBe(200);
    await page.request.post('/__test/weather-worker');
    await page.goto('/plants/' + plants[0]);
    const basis = page.getByRole('region', { name: 'Based on', exact: true });
    await basis.getByText('Low-temperature alerts', { exact: true }).click();
    await expect(basis).toContainText('Hardiness source: override · 6 °C');
    await expect(basis).toContainText('Applicable hardiness: 11 °C');
    await expect(basis).not.toContainText('USDA zone conversion is uncertain');
    await page.goto('/species/' + key);
    await expect(months).toContainText('Hardiness source: override · 6 °C');
    await expect(months).toContainText('June, July, August');
    await expect(months).toContainText('May, June, July, August, September');
    await expect(months).not.toContainText('USDA zone conversion is uncertain');
    await screenshot('weather-hardiness-celsius.png');
  } finally {
    await page.request.put('/api/household/location', { data: { location: null } });
    for (const id of plants) {
      const plant = await (await page.request.get('/api/plants/' + id)).json();
      await page.request.delete('/api/plants/' + id, { data: { confirm_name: plant.display_name } });
    }
    for (const id of sites) await page.request.delete('/api/sites/' + id);
    await page.request.post('/__test/clock', { data: { action: 'reset' } });
  }
});
