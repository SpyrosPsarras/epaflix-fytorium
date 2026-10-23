import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { logIn } from './helpers/login';
import { addPlantForSite } from './helpers/plants';

test('cares for several task types, customizes intervals on a phone', async ({ page }, testInfo) => {
  const name = 'Test multi-care plant ' + randomUUID();
  const siteName = 'Test multi-care site ' + randomUUID();
  const screenshot = async (file: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(file), fullPage: true });
  };
  try {
    await page.request.post('/__test/clock', { data: { action: 'set', at: '2026-05-01T12:00:00Z' } });
    await logIn(page, 'synthetic-browser-owner', { name: 'Test browser member' });
    await page.request.put('/api/tasks/settings', { data: { sort: 'task', filter: 'all', show_completed: false } });
    await page.goto('/sites/new');
    await page.getByLabel('Name', { exact: true }).fill(siteName);
    await page.getByRole('button', { name: 'Save site', exact: true }).click();
    await expect(page).toHaveURL('/sites');
    await addPlantForSite(page.request, siteName, name);
    await page.request.post('/__test/clock', { data: { action: 'set', at: '2026-06-05T12:00:00Z' } });
    // Advancing five weeks expires the seven-day session, so sign in again.
    await logIn(page, 'synthetic-browser-owner', { name: 'Test browser member' });
    await page.goto('/');
    const row = (type: string) => page.locator(`article[data-task-type="${type}"]`).filter({ has: page.getByRole('link', { name, exact: true }) });
    for (const type of ['water', 'fertilize', 'clean_leaves', 'remove_dead']) await expect(row(type)).toBeVisible();
    await screenshot('all-tasks-today.png');
    await row('clean_leaves').getByRole('button', { name: 'Done', exact: true }).click();
    await expect(row('clean_leaves')).toContainText('Next leaf cleaning: 2026-07-05');
    await expect(row('clean_leaves').getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
    // Completing a second task for this plant must not overwrite the first task's Undo state.
    await row('remove_dead').getByRole('button', { name: 'Done', exact: true }).click();
    await expect(row('clean_leaves').getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
    await row('remove_dead').getByRole('button', { name: 'Undo', exact: true }).click();
    await screenshot('all-tasks-clean-done.png');
    await row('clean_leaves').getByRole('link', { name, exact: true }).click();
    await expect(page.getByText('Next leaf cleaning: 2026-07-05', { exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Plant settings', exact: true }).click();
    await page.getByLabel('Clean leaves interval in days', { exact: true }).fill('5');
    await page.getByRole('button', { name: 'Save care settings', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('Care settings saved.');
    await screenshot('all-tasks-custom-settings.png');
    await page.getByRole('link', { name: 'Back to plant', exact: true }).click();
    await expect(page.getByText('Next leaf cleaning: 2026-06-10', { exact: true })).toBeVisible();
    const basedOn = page.getByRole('region', { name: 'Based on', exact: true });
    await expect(basedOn).toBeVisible();
    await basedOn.getByText('Water', { exact: true }).click();
    await expect(basedOn).toContainText('site.light');
    await expect(basedOn).toContainText('pot.pot_type');
    await expect(basedOn).toContainText('plant.plant_size');
    await expect(basedOn).toContainText('month');
    await expect(basedOn).toContainText('water.month@1');
    await basedOn.getByText('Clean leaves', { exact: true }).click();
    await expect(basedOn).toContainText('Source: override');
    await expect(basedOn).toContainText('Source: setting');
    await expect(basedOn).not.toContainText('Source: weather');
    await screenshot('all-tasks-based-on.png');
    const plantUrl = page.url();
    await page.getByRole('link', { name: 'Plant settings', exact: true }).click();
    await page.getByLabel('Clean leaves interval in days', { exact: true }).fill('');
    await page.getByRole('button', { name: 'Save care settings', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('Care settings saved.');
    const plantId = new URL(plantUrl).pathname.split('/').pop();
    const settings = await page.request.get(`/api/plants/${plantId}/care-settings`);
    expect((await settings.json()).customIntervals).not.toHaveProperty('clean_leaves');
    await page.reload();
    await expect(page.getByLabel('Clean leaves interval in days', { exact: true })).toHaveValue('');
    await page.getByRole('link', { name: 'Back to plant', exact: true }).click();
    await expect(page.getByText('Next leaf cleaning: 2026-07-05', { exact: true })).toBeVisible();
    await page.goto('/');
    await row('fertilize').getByRole('button', { name: 'Done', exact: true }).click();
    await expect(row('water')).toHaveCount(0);
    await page.getByRole('link', { name: 'Upcoming', exact: true }).click();
    await expect(row('water')).toContainText('Due 2026-06-12');
    await row('water').getByRole('link', { name, exact: true }).click();
    await expect(page.getByText('Next watering: 2026-06-12', { exact: true })).toBeVisible();
    const fertilized = page.getByRole('region', { name: 'Care history', exact: true }).getByRole('listitem').filter({ hasText: 'Fertilize' });
    await fertilized.getByRole('button', { name: 'Undo', exact: true }).click();
    await expect(page.getByText('Next watering: 2026-05-01', { exact: true })).toBeVisible();
  } finally {
    await page.request.post('/__test/clock', { data: { action: 'reset' } });
  }
});
