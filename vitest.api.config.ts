import { defineConfig } from 'vitest/config';
// Route coverage imports publicRoute from server policy, whose dependencies use $lib.
export default defineConfig({ resolve: { alias: { $lib: new URL('./src/lib', import.meta.url).pathname } }, test: { include: ['tests/api/**/*.test.ts'], testTimeout: 30000, hookTimeout: 120000, fileParallelism: false } });
