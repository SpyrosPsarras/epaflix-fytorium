import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { logIn } from './helpers/login';

test('every role can find Android releases and Obtainium in personal settings', async ({ page, browser }, testInfo) => {
  const checkLinks = async (member: Page) => {
    await member.goto('/settings/notifications');
    await expect(member.getByRole('link', { name: 'Get the Android app', exact: true })).toHaveAttribute('href', 'https://github.com/SpyrosPsarras/epaflix-fytorium/releases');
    await expect(member.getByRole('link', { name: 'Set up Obtainium', exact: true })).toHaveAttribute('href', 'https://github.com/SpyrosPsarras/epaflix-fytorium/blob/main/android/README.md#obtainium');
  };
  await logIn(page, 'synthetic-browser-owner');
  await checkLinks(page);
  for (const role of ['family', 'sitter']) {
    const response = await page.request.post('/api/household/invites', { data: { role, plant_ids: [] } });
    expect(response.status()).toBe(201);
    const invite = await response.json();
    const context = await browser.newContext({ ...testInfo.project.use, baseURL: 'http://app:3000' });
    try {
      const member = await context.newPage();
      await member.goto(invite.link);
      await expect(member).toHaveURL(url => url.pathname === '/default/authorize');
      await member.getByPlaceholder('Enter any user/subject').fill('synthetic-android-' + role + '-' + randomUUID());
      await member.getByRole('button', { name: 'Sign-in', exact: true }).click();
      await expect(member.getByRole('button', { name: 'Log out', exact: true })).toBeVisible();
      await checkLinks(member);
    } finally { await context.close(); }
  }
});
