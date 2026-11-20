import { expect, test } from '@playwright/test';
import { logIn } from './helpers/login';

test('name search describes an unstored Wikidata result without calling it stored', async ({ page }) => {
  await logIn(page, 'synthetic-browser-owner');
  await page.goto('/plants/new');
  await page.getByLabel('Species name', { exact: true }).fill('rubber plant');
  await page.getByRole('button', { name: 'Search species', exact: true }).click();
  const result = page.getByRole('article').filter({ has: page.getByRole('button', { name: 'Choose Ficus elastica', exact: true }) });
  await expect(result.getByText('Matched Rubber plant · English')).toBeVisible();
  await expect(result.getByText('Stored species', { exact: false })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Wikidata', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'GBIF', exact: true })).toBeVisible();
});
