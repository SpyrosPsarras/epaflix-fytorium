import { defineConfig, devices } from '@playwright/test';

const keep = process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1';
export default defineConfig({
  testDir: './tests/browser',
  outputDir: './test-artifacts',
  reporter: 'list',
  workers: 1,
  use: {
    ...devices['Pixel 7'],
    baseURL: 'http://app:3000',
    channel: 'chromium',
    launchOptions: { args: ['--unsafely-treat-insecure-origin-as-secure=http://app:3000'] },
    screenshot: keep ? 'on' : 'only-on-failure',
    video: keep ? 'on' : 'retain-on-failure',
    trace: keep ? 'on' : 'retain-on-failure',
  },
});
