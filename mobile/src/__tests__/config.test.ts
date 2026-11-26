import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolve } from '../../app.config';

const firebase = (projectId: string) => {
  const file = join(mkdtempSync(join(tmpdir(), 'fytorium-')), 'google-services.json');
  writeFileSync(file, JSON.stringify({
    project_info: { project_id: projectId },
    client: [{ client_info: { android_client_info: { package_name: 'com.epaflix.fytorium' } } }],
  }));
  return file;
};

test('debug uses the synthetic Firebase config and the stack origin', () => {
  expect(resolve({})).toEqual({ variant: 'debug', serverUrl: 'http://app:3000', googleServicesFile: './firebase/google-services.synthetic.json' });
});

test('release defaults to the production HTTPS origin', () => {
  expect(resolve({ FYTORIUM_VARIANT: 'release', GOOGLE_SERVICES_FILE: firebase('fytorium-prod') }).serverUrl).toBe('https://fytorium.epaflix.com');
});

test('release refuses a cleartext server URL', () => {
  expect(() => resolve({ FYTORIUM_VARIANT: 'release', FYTORIUM_SERVER_URL: 'http://app:3000', GOOGLE_SERVICES_FILE: firebase('p') }))
    .toThrow('Release server URL must use https://');
});

test.each(['https://', 'http://fytorium.epaflix.com', 'ftp://fytorium.epaflix.com'])('release refuses the malformed URL %s', (url) => {
  expect(() => resolve({ FYTORIUM_VARIANT: 'release', FYTORIUM_SERVER_URL: url, GOOGLE_SERVICES_FILE: firebase('p') }))
    .toThrow('Release server URL must use https://');
});

test.each(['https://app:3000', 'https://oidc', 'https://fake-push:8080', 'https://localhost'])('release refuses the test host %s', (url) => {
  expect(() => resolve({ FYTORIUM_VARIANT: 'release', FYTORIUM_SERVER_URL: url, GOOGLE_SERVICES_FILE: firebase('p') }))
    .toThrow('Release server URL must not be a test host');
});

test('release needs a Firebase config', () => {
  expect(() => resolve({ FYTORIUM_VARIANT: 'release' })).toThrow('GOOGLE_SERVICES_FILE is required for release');
});

test('release refuses a synthetic Firebase config unless the test flag is set', () => {
  const file = firebase('synthetic-fytorium-do-not-deploy');
  expect(() => resolve({ FYTORIUM_VARIANT: 'release', GOOGLE_SERVICES_FILE: file })).toThrow('Synthetic Firebase config cannot be released');
  expect(resolve({ FYTORIUM_VARIANT: 'release', GOOGLE_SERVICES_FILE: file, FYTORIUM_FIREBASE_TEST_CONFIG: 'true' }).variant).toBe('release');
});

test('release refuses a Firebase config for another package', () => {
  const file = join(mkdtempSync(join(tmpdir(), 'fytorium-')), 'google-services.json');
  writeFileSync(file, JSON.stringify({ project_info: { project_id: 'p' }, client: [] }));
  expect(() => resolve({ FYTORIUM_VARIANT: 'release', GOOGLE_SERVICES_FILE: file })).toThrow('Invalid Firebase config');
});
