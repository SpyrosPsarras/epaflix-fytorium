import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { logIn } from './helpers/login';
import { addPlantForSite } from './helpers/plants';

test('sees late watering, completes, undoes and checks the member history on a phone', async ({ page }, testInfo) => {
  const name = 'Test watering sprout ' + randomUUID();
  const siteName = 'Test watering site ' + randomUUID();
  const screenshot = async (file: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(file), fullPage: true });
  };
  try {
    expect((await page.request.post('/__test/clock', { data: { action: 'set', at: '2026-10-24T22:30:00Z' } })).ok()).toBe(true);
    await logIn(page, 'synthetic-browser-owner', { name: 'Test browser member' });
    await page.goto('/sites/new');
    await page.getByLabel('Name', { exact: true }).fill(siteName);
    await page.getByRole('button', { name: 'Save site', exact: true }).click();
    await addPlantForSite(page.request, siteName, name);
    expect((await page.request.post('/__test/clock', { data: { action: 'set', at: '2026-10-28T12:00:00Z' } })).ok()).toBe(true);
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Today', exact: true })).toBeVisible();
    const row = page.getByRole('article').filter({ has: page.getByRole('link', { name, exact: true }) });
    await expect(row).toContainText(siteName);
    await expect(row).toContainText('3d late');
    await screenshot('watering-today-late.png');
    await row.getByRole('button', { name: 'Done', exact: true }).click();
    await expect(row).not.toContainText('3d late');
    await expect(row.getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
    await screenshot('watering-done-undo.png');
    await row.getByRole('button', { name: 'Undo', exact: true }).click();
    await expect(row).toContainText('3d late');
    await screenshot('watering-today-restored.png');
    await row.getByRole('link', { name, exact: true }).click();
    await expect(page.getByText('Next watering: 2026-10-25', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Care history', exact: true })).toBeVisible();
    const history = page.getByRole('region', { name: 'Care history', exact: true });
    await expect(history).toContainText('2026-10-28');
    await expect(history).toContainText('Water');
    await expect(history).toContainText('Completed by Test browser member · Undone');
    await expect(history).toContainText('Undone');
    await screenshot('watering-history-undone.png');
  } finally {
    expect((await page.request.post('/__test/clock', { data: { action: 'reset' } })).ok()).toBe(true);
  }
});
