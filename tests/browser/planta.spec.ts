import { expect, test } from '@playwright/test';
import { logIn } from './helpers/login';

test('imports Planta plants with fuzzy confirmation, cover photos and continued care dates on a phone', async ({ page }, testInfo) => {
  const screenshot = async (name: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(name), fullPage: true });
  };
  await logIn(page, 'synthetic-browser-owner');
  await page.request.post('/__test/clock', { data: { action: 'set', at: '2026-06-10T12:00:00Z' } });
  try {
    await page.getByRole('link', { name: 'Import from Planta', exact: true }).click();
    await page.getByLabel('One-time app code', { exact: true }).fill('synthetic-browser-import');
    await screenshot('planta-code.png');
    await page.getByRole('button', { name: 'Fetch Planta plants', exact: true }).click();
    const fuzzy = page.getByRole('article').filter({ has: page.getByRole('heading', { name: 'Test import amber', exact: true }) });
    await expect(fuzzy).toContainText('Needs review');
    await expect(page.getByRole('button', { name: 'Import confirmed plants', exact: true })).toBeDisabled();
    await screenshot('planta-review.png');
    await fuzzy.getByRole('button', { name: 'Confirm Testoria nebulosa', exact: true }).click();
    await expect(fuzzy).toContainText('Confirmed species: Testoria nebulosa');
    await page.getByRole('button', { name: 'Import confirmed plants', exact: true }).click();
    await expect(page.getByText('Import complete.', { exact: true })).toBeVisible();
    await screenshot('planta-committed.png');
    await page.getByRole('link', { name: 'View imported plants', exact: true }).click();
    await page.getByLabel('Search plants', { exact: true }).fill('Test import amber');
    const cover = page.getByRole('img', { name: 'Cover photo of Test import amber', exact: true });
    await expect(cover).toBeVisible();
    await expect.poll(() => cover.evaluate(element => element instanceof HTMLImageElement && element.complete && element.naturalWidth > 0)).toBe(true);
    await screenshot('planta-collection.png');
    await page.getByRole('link', { name: 'Test import amber', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Test import amber', exact: true, level: 1 })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Water', exact: true })).toContainText('2026-06-08');
    await page.getByRole('region', { name: 'Based on', exact: true }).getByText('Water', { exact: true }).click();
    await expect(page.getByRole('region', { name: 'Based on', exact: true })).toContainText('counted from last completion on 2026-06-01');
    await screenshot('planta-next-due.png');
    await page.goto('/plants');
    await page.getByLabel('Search plants', { exact: true }).fill('Test import blue');
    await expect(page.getByRole('link', { name: 'Test import blue', exact: true })).toBeVisible();
  } finally { await page.request.post('/__test/clock', { data: { action: 'reset' } }); }
});
