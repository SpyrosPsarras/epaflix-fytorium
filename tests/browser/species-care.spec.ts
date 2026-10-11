import { expect, test } from '@playwright/test';
import { logIn } from './helpers/login';

test('species care arrives after add, then an override reaches the card and Based on on a phone', async ({ page }, testInfo) => {
  test.setTimeout(90000);
  const key = 100000000 + Math.floor(Math.random() * 800000000);
  const name = 'Testoria sourced ' + key;
  const screenshot = async (file: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(file), fullPage: true });
  };
  await page.request.post('/__test/clock', { data: { action: 'set', at: '2030-06-01T12:00:00Z' } });
  await logIn(page, 'synthetic-browser-owner');
  try {
    const site = await (await page.request.post('/api/sites', { data: { name: 'Test sourced room ' + key, location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false } })).json();
    await page.goto('/plants/new');
    await page.getByLabel('Species name', { exact: true }).fill(name);
    await page.getByRole('button', { name: 'Search species', exact: true }).click();
    await page.getByRole('button', { name: 'Choose ' + name, exact: true }).click();
    await page.getByLabel('Site', { exact: true }).selectOption(site.id);
    await page.getByLabel('Pot size in cm', { exact: true }).fill('12');
    await page.getByLabel('Growth stage', { exact: true }).selectOption('young');
    await page.getByLabel('Drainage', { exact: true }).check();
    await page.getByRole('button', { name: 'Save plant', exact: true }).click();
    await expect(page).toHaveURL(/\/plants\/[0-9a-f-]+$/);
    const plant = { id: page.url().split('/').pop()! };
    await page.getByRole('link', { name: 'Species care and names', exact: true }).click();
    const water = page.getByRole('article', { name: 'Watering interval in days', exact: true });
    await expect(water).toContainText('Source: rule');
    await expect(water.locator('.value')).toHaveText('7');
    await screenshot('species-care-rules.png');
    // The actual in-process worker handles this queued request; WireMock delays details.
    await expect(water).toContainText('Source: perenual', { timeout: 45000 });
    await expect(water.locator('.value')).toHaveText('12');
    await expect(water).toContainText('Fetch time: 2030-06-01T12:00:00.000Z');
    await screenshot('species-care-perenual.png');
    await page.goto('/plants/' + plant.id);
    const perenualBasis = page.getByRole('region', { name: 'Based on', exact: true });
    await perenualBasis.getByText('Water', { exact: true }).click();
    await expect(perenualBasis).toContainText('Source: perenual');
    await expect(page.getByText('Every 9 days', { exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Species care and names', exact: true }).click();
    await water.getByText('Override watering interval in days', { exact: true }).click();
    await water.getByLabel('Override value for watering interval in days', { exact: true }).fill('20');
    await water.getByRole('button', { name: 'Save override', exact: true }).click();
    await expect(water).toContainText('Source: override');
    await expect(water.locator('.value')).toHaveText('20');
    await screenshot('species-care-override.png');
    await page.goto('/plants/' + plant.id);
    const basis = page.getByRole('region', { name: 'Based on', exact: true });
    await basis.getByText('Water', { exact: true }).click();
    await expect(basis).toContainText('Source: override');
    await expect(basis).toContainText('20');
    await expect(page.getByText('Every 15 days', { exact: true })).toBeVisible();
    await screenshot('species-care-based-on.png');
  } finally { await page.request.post('/__test/clock', { data: { action: 'reset' } }); }
});
