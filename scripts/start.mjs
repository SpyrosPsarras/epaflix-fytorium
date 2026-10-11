import { spawnSync } from 'node:child_process';

await import('../build/handler.js');

const migration = spawnSync('dbmate', ['--wait', '--no-dump-schema', '--migrations-dir', 'migrations', 'migrate'], { stdio: 'inherit' });
if (migration.error) throw migration.error;
if (migration.status !== 0) process.exit(migration.status ?? 1);
await import('../build/index.js');
