import { readFileSync } from 'node:fs';
import { config } from '../config';
import { FcmTransport, type ServiceAccount } from './fcm';

export function fcmConfigured() {
  const settings = config();
  return !!settings.fcmProjectId && !!(settings.fcmServiceAccountJson || settings.fcmServiceAccountFile);
}
let transport: FcmTransport | undefined;
let warned = false;
export function fcmTransport() {
  if (!fcmConfigured()) {
    if (!warned) { console.info('FCM is not configured; app devices skipped.'); warned = true; }
    return null;
  }
  if (!transport) {
    const settings = config();
    // Tests may never fall back to Google, even when only part of the fake config was provided.
    if (settings.testClockEnabled && [settings.fcmBaseUrl, settings.fcmTokenUrl].some(url => {
      const host = new URL(url).hostname;
      return host !== 'fake-push' && host !== '127.0.0.1' && host !== 'localhost';
    })) throw new Error('FCM tests require local fake endpoints');
    try {
      const account: ServiceAccount = JSON.parse(settings.fcmServiceAccountJson || readFileSync(settings.fcmServiceAccountFile, 'utf8'));
      if (!account.client_email || !account.private_key) throw new Error();
      transport = new FcmTransport({ baseUrl: settings.fcmBaseUrl, tokenUrl: settings.fcmTokenUrl, projectId: settings.fcmProjectId, account });
    } catch { throw new Error('Invalid FCM service account configuration'); }
  }
  return transport;
}
