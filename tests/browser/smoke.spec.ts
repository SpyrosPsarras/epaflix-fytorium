import { expect, test } from '@playwright/test';
import { logIn } from './helpers/login';

test('a chosen OIDC subject sees the empty home and install metadata', async ({ page, request }) => {
  await logIn(page, 'synthetic-browser-owner');
  await expect(page.getByRole('heading', { name: 'No plants yet' })).toBeVisible();
  await expect(page.getByText('My household · owner')).toBeVisible();
  const href = await page.locator('link[rel="manifest"]').getAttribute('href');
  expect(href).toBe('/manifest.webmanifest');
  const manifest = await (await request.get(href!)).json();
  expect(manifest).toMatchObject({ name: 'Fytorium', display: 'standalone', start_url: '/' });
  for (const icon of manifest.icons) {
    const response = await request.get(icon.src);
    expect(response.ok()).toBe(true);
    expect(response.headers()['content-type'].startsWith('image/')).toBe(true);
  }
  await expect.poll(() => page.evaluate(async () => {
    const registration = await navigator.serviceWorker.getRegistration();
    return registration?.active?.scriptURL;
  })).toBe('http://app:3000/service-worker.js');
});
