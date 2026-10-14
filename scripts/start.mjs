import { spawnSync } from 'node:child_process';

// The disposable stack gets a generated test key pair from its fake receiver.
// Production keys must be configured by the operator and survive app restarts.
if (process.env.NODE_ENV === 'test' && process.env.FYTORIUM_TEST_CLOCK === 'true' && process.env.FYTORIUM_TEST_PUSH_URL && !process.env.VAPID_PUBLIC_KEY) {
  const response = await fetch(new URL('vapid', process.env.FYTORIUM_TEST_PUSH_URL), { signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error('Test push receiver is unavailable');
  const keys = await response.json();
  process.env.VAPID_PUBLIC_KEY = keys.publicKey;
  process.env.VAPID_PRIVATE_KEY = keys.privateKey;
}
if (process.env.NODE_ENV === 'test' && process.env.FYTORIUM_TEST_CLOCK === 'true' && process.env.FCM_BASE_URL === 'http://fake-push:8080' && !process.env.FCM_SERVICE_ACCOUNT_JSON) {
  const response = await fetch('http://fake-push:8080/fcm/account', { signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error('Fake FCM is unavailable');
  process.env.FCM_SERVICE_ACCOUNT_JSON = JSON.stringify(await response.json());
}
await import('../build/handler.js');

const migration = spawnSync('dbmate', ['--wait', '--no-dump-schema', '--migrations-dir', 'migrations', 'migrate'], { stdio: 'inherit' });
if (migration.error) throw migration.error;
if (migration.status !== 0) process.exit(migration.status ?? 1);
await import('../build/index.js');
