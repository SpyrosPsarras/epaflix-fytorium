import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { logIn } from './helpers/login';
import { fixturePlant } from '../plant-fixture';

test('owner invites a sitter for one plant and removes their access on a phone', async ({ page, browser }, testInfo) => {
  const suffix = randomUUID();
  await logIn(page, 'synthetic-browser-owner');
  const site = await (await page.request.post('/api/sites', { data: { name: 'Sharing room ' + suffix, location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false } })).json();
  const search = await (await page.request.get('/api/species/search?q=Testoria%20lucida')).json();
  const add = async (nickname: string) => (await (await page.request.post('/api/plants', { data: { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname } })).json());
  const shared = await add('Sitter fern ' + suffix);
  const unshared = await add('Private fern ' + suffix);
  await page.goto('/members');
  await page.getByRole('combobox', { name: 'Role', exact: true }).selectOption('sitter');
  await page.getByRole('group', { name: 'Share plants', exact: true }).getByRole('checkbox', { name: shared.display_name, exact: true }).check();
  await page.getByRole('button', { name: 'Create invite', exact: true }).click();
  const linkField = page.getByLabel('Invite link', { exact: true });
  await expect(linkField).toHaveValue(/\/invite\//);
  const link = await linkField.inputValue();
  const screenshot = async (target: typeof page, name: string) => {
    expect(await target.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await target.screenshot({ path: testInfo.outputPath(name), fullPage: true });
  };
  // Never put an active invite token in a retained screenshot.
  await page.getByRole('button', { name: 'Hide link', exact: true }).click();
  await screenshot(page, 'sharing-owner-invite.png');
  const context = await browser.newContext({ ...testInfo.project.use, baseURL: 'http://app:3000' });
  const sitter = await context.newPage();
  try {
    await sitter.goto(link);
    await expect(sitter).toHaveURL(url => url.pathname === '/default/authorize');
    await sitter.getByPlaceholder('Enter any user/subject').fill('synthetic-sitter-' + suffix);
    await sitter.locator('textarea[name=claims]').fill(JSON.stringify({ name: 'Test sitter ' + suffix }));
    await sitter.getByRole('button', { name: 'Sign-in', exact: true }).click();
    await expect(sitter.getByRole('heading', { name: 'Today', exact: true })).toBeVisible();
    await expect(sitter.getByRole('link', { name: shared.display_name, exact: true })).toBeVisible();
    await expect(sitter.getByRole('link', { name: unshared.display_name, exact: true })).toHaveCount(0);
    await expect(sitter.getByRole('link', { name: 'Plants', exact: true })).toHaveCount(0);
    await screenshot(sitter, 'sharing-sitter-today.png');
    const row = sitter.locator('article[data-task-type="water"]').filter({ has: sitter.getByRole('link', { name: shared.display_name, exact: true }) });
    await row.getByRole('button', { name: 'Done', exact: true }).click();
    await expect(row.getByRole('button', { name: 'Undo', exact: true })).toBeVisible();
    await screenshot(sitter, 'sharing-sitter-completed.png');
    expect((await sitter.goto('/plants/' + unshared.id))?.status()).toBe(404);
    await screenshot(sitter, 'sharing-unshared-refused.png');
    await page.bringToFront();
    await page.reload();
    const member = page.getByRole('article', { name: 'Test sitter ' + suffix, exact: true });
    const removed = page.waitForResponse(response => response.url().includes('/api/household/members/') && response.request().method() === 'DELETE');
    page.once('dialog', dialog => dialog.accept());
    await member.getByRole('button', { name: 'Remove member', exact: true }).click();
    expect((await removed).status()).toBe(204);
    await expect(member).toHaveCount(0);
    await screenshot(page, 'sharing-owner-removed.png');
    expect((await sitter.request.get('/api/tasks')).status()).toBe(401);
    expect((await sitter.request.get('/plants/' + shared.id, { maxRedirects: 0 })).status()).toBe(303);
    await sitter.goto('/plants/' + shared.id);
    await expect(sitter).toHaveURL(url => url.origin === 'http://oidc:8080' && url.pathname === '/default/authorize');
    await screenshot(sitter, 'sharing-removed-refused.png');
  } finally { await context.close(); }
});
