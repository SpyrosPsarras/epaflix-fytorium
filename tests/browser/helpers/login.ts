import { expect, type Page } from '@playwright/test';

export async function logIn(page: Page, subject: string) {
  await page.goto('/');
  await page.getByRole('link', { name: 'Log in' }).click();
  await expect(page).toHaveURL(url => url.origin === 'http://oidc:8080' && url.pathname === '/default/authorize');
  await page.getByPlaceholder('Enter any user/subject').fill(subject);
  await page.getByRole('button', { name: 'Sign-in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'No plants yet' })).toBeVisible();
}
