import { createSign } from 'node:crypto';
import type { PushPayload } from './delivery';

export type ServiceAccount = { client_email: string; private_key: string };
type Settings = { baseUrl: string; tokenUrl: string; projectId: string; account: ServiceAccount };
export class FcmError extends Error {
  readonly permanent: boolean;
  constructor(readonly statusCode: number, code?: string) {
    super('FCM delivery failed.');
    // A generic INVALID_ARGUMENT can mean a malformed payload, not a bad registration token.
    this.permanent = statusCode === 404 || code === 'UNREGISTERED' || code === 'INVALID_ARGUMENT';
  }
}

export class FcmTransport {
  private cached?: { token: string; until: number };
  private pending?: Promise<string>;
  constructor(private readonly settings: Settings, private readonly clock = Date.now) {}
  private async accessToken() {
    if (this.cached && this.cached.until > this.clock()) return this.cached.token;
    this.pending ??= this.authorize().finally(() => { this.pending = undefined; });
    return this.pending;
  }
  private async authorize() {
    const { account, tokenUrl } = this.settings;
    const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
    const issued = Math.floor(Date.now() / 1000);
    const unsigned = encode({ alg: 'RS256', typ: 'JWT' }) + '.' + encode({ iss: account.client_email,
      scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: tokenUrl, iat: issued, exp: issued + 3600 });
    const signature = createSign('RSA-SHA256').update(unsigned).sign(account.private_key, 'base64url');
    const response = await fetch(tokenUrl, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(5000),
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: unsigned + '.' + signature }) });
    if (!response.ok) { await response.body?.cancel(); throw new FcmError(response.status); }
    const result = await response.json();
    if (typeof result.access_token !== 'string' || !Number.isFinite(result.expires_in) || result.expires_in <= 0) throw new FcmError(502);
    this.cached = { token: result.access_token, until: this.clock() + result.expires_in * 1000 };
    return result.access_token;
  }
  async send(token: string, payload: PushPayload) {
    const { baseUrl, projectId } = this.settings;
    const access = await this.accessToken();
    const response = await fetch(new URL(`/v1/projects/${encodeURIComponent(projectId)}/messages:send`, baseUrl), {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(5000),
      headers: { authorization: `Bearer ${access}`, 'content-type': 'application/json' },
      body: JSON.stringify({ message: { token, data: payload } }),
    });
    if (response.ok) { await response.body?.cancel(); return; }
    if (response.status === 401) this.cached = undefined;
    const result = await response.json().catch(() => ({}));
    const details: unknown = result.error?.details;
    const detail = Array.isArray(details) ? details.find(item => item?.['@type'] === 'type.googleapis.com/google.firebase.fcm.v1.FcmError') : undefined;
    throw new FcmError(response.status, detail?.errorCode);
  }
}
