import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { logIn } from './helpers/login';
import { addPlantForSite } from './helpers/plants';

async function addSproutOnMay1(page: Page, label: string) {
  const name = `Test ${label} sprout ${randomUUID()}`;
  const siteName = `Test ${label} room ${randomUUID()}`;
  await page.request.post('/__test/clock', { data: { action: 'set', at: '2026-05-01T12:00:00Z' } });
  await logIn(page, 'synthetic-browser-owner', { name: 'Test browser member' });
  await page.goto('/sites/new');
  await page.getByLabel('Name', { exact: true }).fill(siteName);
  await page.getByRole('button', { name: 'Save site', exact: true }).click();
  await expect(page).toHaveURL('/sites');
  await addPlantForSite(page.request, siteName, name);
  const plant = (await (await page.request.get('/api/plants')).json()).find((item: { display_name: string }) => item.display_name === name);
  const row = (type: string) => page.locator(`article[data-task-type="${type}"]`).filter({ has: page.getByRole('link', { name, exact: true }) });
  return { name, plantId: plant.id as string, row };
}
// The Undo request waits until released; the next page's data is built by the server before the Undo is saved.
async function holdUndoAndData(page: Page, dataPath: string) {
  let saveUndo = () => {};
  let deliverData = () => {};
  const undoHeld = new Promise<void>(resolve => { saveUndo = resolve; });
  const dataHeld = new Promise<void>(resolve => { deliverData = resolve; });
  const undoUrl = '**/api/care-events/*/undo';
  const dataUrl = (url: URL) => url.pathname === dataPath;
  await page.route(undoUrl, async route => { await undoHeld; await route.continue(); });
  await page.route(dataUrl, async route => { const response = await route.fetch(); await dataHeld; await route.fulfill({ response }); });
  return { saveUndo, deliverData, unroute: async () => { await page.unroute(undoUrl); await page.unroute(dataUrl); } };
}
async function logInOnJune5(page: Page) {
  await page.request.post('/__test/clock', { data: { action: 'set', at: '2026-06-05T12:00:00Z' } });
  await logIn(page, 'synthetic-browser-owner', { name: 'Test browser member' });
  await page.request.put('/api/tasks/settings', { data: { sort: 'task', filter: 'all', show_completed: false } });
}

test('snoozes, skips, plans upcoming care, keeps filters, handles all tasks and repots on a phone', async ({ page }, testInfo) => {
  const screenshot = async (file: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(file), fullPage: true });
  };
  try {
    const { name, row } = await addSproutOnMay1(page, 'handling');
    await logInOnJune5(page);
    await page.goto('/');
    await row('water').getByRole('button', { name: 'Snooze 2 days', exact: true }).click();
    await expect(row('water')).toContainText('Snoozed');
    await expect(row('water')).toContainText('2026-06-07');
    await screenshot('handling-snoozed.png');
    await row('water').getByRole('button', { name: 'Undo', exact: true }).click();
    await row('water').getByRole('button', { name: 'Skip', exact: true }).click();
    await expect(row('water')).toContainText('Skipped');
    await expect(row('water')).toContainText('2026-06-12');
    await page.getByRole('link', { name: 'Upcoming', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Upcoming', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: '2026-06-05', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: '2026-06-12', exact: true })).toBeVisible();
    await expect(row('water')).toContainText('2026-06-12');
    await expect(row('water').getByRole('button', { name: 'Snooze 2 days', exact: true })).toHaveCount(0);
    await page.getByLabel('Sort tasks', { exact: true }).selectOption('site');
    await page.getByLabel('Filter location', { exact: true }).selectOption('indoor');
    await page.getByLabel('Show others\' completed tasks', { exact: true }).check();
    await page.getByRole('button', { name: 'Save task view', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Task view saved.' })).toHaveText('Task view saved.');
    await page.reload();
    await expect(page.getByLabel('Sort tasks', { exact: true })).toHaveValue('site');
    await expect(page.getByLabel('Filter location', { exact: true })).toHaveValue('indoor');
    await expect(page.getByLabel('Show others\' completed tasks', { exact: true })).toBeChecked();
    await screenshot('handling-upcoming-grouped.png');
    await row('water').getByRole('link', { name, exact: true }).click();
    await page.getByRole('button', { name: 'Handle all tasks', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('Handled 4 tasks.');
    await expect(page.getByText('Next watering: 2026-06-12', { exact: true })).toBeVisible();
    await expect(page.getByText('Next leaf cleaning: 2026-07-05', { exact: true })).toBeVisible();
    const history = page.getByRole('region', { name: 'Care history', exact: true });
    const batchHint = history.getByText('Part of a care batch. Undo reverses the whole batch.', { exact: true });
    await expect(batchHint).toHaveCount(4);
    await expect(history.getByRole('button', { name: 'Undo all 4', exact: true })).toHaveCount(4);
    await screenshot('handling-all-done.png');
    await page.goto('/');
    // Liquid fertilizing also waters, so the batch is fertilize, its water, clean leaves and remove dead.
    const batched = page.locator('article').filter({ has: page.getByRole('link', { name, exact: true }) }).filter({ hasText: `Handled 4 tasks for ${name}` });
    await expect(batched).toHaveCount(4);
    await expect(batched.getByRole('button', { name: 'Undo all 4', exact: true })).toHaveCount(4);
    await expect(batched.getByRole('button', { name: 'Undo', exact: true })).toHaveCount(0);
    await row('clean_leaves').getByRole('button', { name: 'Undo all 4', exact: true }).click();
    await expect(row('clean_leaves').getByRole('button', { name: 'Done', exact: true })).toBeVisible();
    await expect(batched).toHaveCount(0);
    await row('clean_leaves').getByRole('link', { name, exact: true }).click();
    await page.getByRole('link', { name: 'Repot with new pot and soil', exact: true }).click();
    await page.getByLabel('Pot type', { exact: true }).selectOption('terracotta');
    await page.getByLabel('Pot size in cm', { exact: true }).fill('24');
    await page.getByLabel('Soil type', { exact: true }).selectOption('aroid');
    await screenshot('handling-repot-form.png');
    await page.getByRole('button', { name: 'Save repot', exact: true }).click();
    await expect(page.getByText('Next repotting: 2029-06-04', { exact: true })).toBeVisible();
    const attributes = page.getByRole('region', { name: 'Pot and plant attributes', exact: true });
    await expect(attributes).toContainText('terracotta');
    await expect(attributes).toContainText('24');
    await expect(attributes).toContainText('aroid');
    await screenshot('handling-repotted.png');
    // The batch was undone from Today, so its entries no longer offer or explain an undo.
    await expect(batchHint).toHaveCount(0);
    await expect(history.getByRole('button', { name: 'Undo all 4', exact: true })).toHaveCount(0);
    const repotEvent = history.getByRole('listitem').filter({ hasText: 'Repot' }).filter({ hasText: 'Completed by' });
    await repotEvent.getByRole('button', { name: 'Undo repotting', exact: true }).click();
    await expect(attributes).toContainText('plastic');
    await expect(page.getByText('Next repotting: 2028-04-30', { exact: true })).toBeVisible();
  } finally {
    await page.request.put('/api/tasks/settings', { data: { sort: 'task', filter: 'all', show_completed: false } });
    await page.request.post('/__test/clock', { data: { action: 'reset' } });
  }
});

test('each occurrence keeps its own result, busy state and Undo after a skipped occurrence is completed', async ({ page }) => {
  try {
    const { plantId, row } = await addSproutOnMay1(page, 'occurrence');
    await logInOnJune5(page);
    const rows = row('water');
    await page.goto('/');
    await rows.getByRole('button', { name: 'Done', exact: true }).click();
    await expect(rows.getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
    // The same member skips the next occurrence from another device.
    expect((await page.request.post(`/api/plants/${plantId}/tasks/water/skip`, { data: { request_id: randomUUID() } })).status()).toBe(200);
    await page.goto('/upcoming');
    await expect(rows).toHaveCount(2);
    const [completed, skipped] = [rows.nth(0), rows.nth(1)];
    await expect(completed.getByRole('button', { name: 'Undo', exact: true })).toBeEnabled();
    let release = () => {};
    const held = new Promise<void>(resolve => { release = resolve; });
    await page.route('**/tasks/water/complete', async route => { await held; await route.continue(); });
    await skipped.getByRole('button', { name: 'Done', exact: true }).click();
    await expect(skipped.getByRole('button', { name: 'Done', exact: true })).toBeDisabled();
    await expect(completed.getByRole('button', { name: 'Undo', exact: true })).toBeEnabled();
    release();
    await expect(skipped.getByRole('status')).toContainText('Done');
    await expect(skipped.getByRole('button', { name: 'Undo', exact: true })).toBeEnabled();
    await expect(completed.getByRole('button', { name: 'Undo', exact: true })).toBeEnabled();
    await page.unroute('**/tasks/water/complete');
    await page.reload();
    await expect(rows).toHaveCount(2);
    await expect(rows.getByRole('button', { name: 'Undo', exact: true })).toHaveCount(2);
    await rows.nth(1).getByRole('button', { name: 'Undo', exact: true }).click();
    await expect(rows.nth(1).getByRole('button', { name: 'Done', exact: true })).toBeVisible();
    await expect(rows.nth(0).getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
    const history = await (await page.request.get(`/api/plants/${plantId}/history`)).json();
    const completions = history.filter((event: { task_type: string; kind: string }) => event.task_type === 'water' && event.kind === 'completed');
    expect(completions.map((event: { undone_at: string | null }) => event.undone_at !== null)).toEqual([true, false]);
  } finally {
    await page.request.put('/api/tasks/settings', { data: { sort: 'task', filter: 'all', show_completed: false } });
    await page.request.post('/__test/clock', { data: { action: 'reset' } });
  }
});

test('after each reload the rows are the server\'s: your completions stay, a postponed row is never checked and one undone elsewhere leaves', async ({ page }) => {
  try {
    const { plantId, row } = await addSproutOnMay1(page, 'reload');
    expect((await page.request.put(`/api/plants/${plantId}/care-settings`, { data: { customIntervals: { water: 7, fertilize: 7, clean_leaves: 7 } } })).status()).toBe(200);
    await logInOnJune5(page);
    expect((await page.request.post(`/api/plants/${plantId}/tasks/fertilize/skip`, { data: { request_id: randomUUID() } })).status()).toBe(200);
    const done = (type: string) => row(type).getByRole('button', { name: 'Done', exact: true });
    const undo = (type: string) => row(type).getByRole('button', { name: 'Undo', exact: true });
    const completions = async (type: string) => (await (await page.request.get(`/api/plants/${plantId}/history`)).json())
      .filter((event: { task_type: string; kind: string }) => event.task_type === type && event.kind === 'completed');
    await page.goto('/upcoming');
    await done('water').click();
    await expect(undo('water')).toBeEnabled();
    // Fertilizing the skipped occurrence reloads the list, which lists the earlier watering and the linked water as two completions.
    await done('fertilize').click();
    await expect(row('fertilize').getByRole('status')).toContainText('Done');
    await expect(undo('fertilize')).toBeEnabled();
    await expect(undo('water')).toHaveCount(2);
    await undo('fertilize').click();
    await expect(undo('water')).toHaveCount(1);
    await done('fertilize').click();
    await expect(undo('fertilize')).toBeEnabled();
    expect((await completions('fertilize')).map((event: { undone_at: string | null }) => event.undone_at !== null)).toEqual([false, true]);
    await done('clean_leaves').click();
    await expect(undo('clean_leaves')).toBeEnabled();
    const [cleaned] = await completions('clean_leaves');
    expect((await page.request.post(`/api/care-events/${cleaned.id}/undo`)).status()).toBe(200);
    expect((await page.request.post(`/api/plants/${plantId}/tasks/water/skip`, { data: { request_id: randomUUID() } })).status()).toBe(200);
    await page.getByRole('button', { name: 'Save task view', exact: true }).click();
    await expect(done('clean_leaves')).toBeVisible();
    await expect(done('water')).toBeVisible();
    await expect(undo('fertilize')).toBeEnabled();
  } finally {
    await page.request.post('/__test/clock', { data: { action: 'reset' } });
  }
});

test('an Undo that finishes while Today switches to Upcoming leaves no completed row there', async ({ page }) => {
  try {
    const { row } = await addSproutOnMay1(page, 'switch');
    await logInOnJune5(page);
    const done = row('water').getByRole('button', { name: 'Done', exact: true });
    const undo = row('water').getByRole('button', { name: 'Undo', exact: true });
    await page.goto('/');
    await done.click();
    await expect(undo).toBeEnabled();
    const held = await holdUndoAndData(page, '/upcoming/__data.json');
    await undo.click();
    await page.getByRole('link', { name: 'Upcoming', exact: true }).click();
    held.saveUndo();
    // The undone row stays busy until its reload lands after the navigation.
    await expect(done).toBeDisabled();
    held.deliverData();
    await expect(page).toHaveURL('/upcoming');
    await expect(done).toBeVisible();
    await expect(undo).toHaveCount(0);
    await held.unroute();
  } finally {
    await page.request.post('/__test/clock', { data: { action: 'reset' } });
  }
});

test('an Undo that finishes during a navigation back to the current page leaves no completed row', async ({ page }) => {
  try {
    const { row } = await addSproutOnMay1(page, 'same page');
    await logInOnJune5(page);
    const done = row('water').getByRole('button', { name: 'Done', exact: true });
    const undo = row('water').getByRole('button', { name: 'Undo', exact: true });
    await page.goto('/upcoming');
    await done.click();
    await expect(undo).toBeEnabled();
    // From here the server lists the completion in this page's own data.
    await page.reload();
    await expect(undo).toBeEnabled();
    const held = await holdUndoAndData(page, '/__data.json');
    await undo.click();
    await page.getByRole('link', { name: 'Today', exact: true }).click();
    held.saveUndo();
    await expect(undo).toBeDisabled();
    // Returning to the current page reuses its data from before the Undo.
    await page.getByRole('link', { name: 'Upcoming', exact: true }).click();
    held.deliverData();
    await expect(done).toBeVisible();
    await expect(undo).toHaveCount(0);
    await expect(page).toHaveURL('/upcoming');
    await held.unroute();
  } finally {
    await page.request.post('/__test/clock', { data: { action: 'reset' } });
  }
});
