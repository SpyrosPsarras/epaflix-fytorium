import * as Crypto from 'expo-crypto';

export const REDIRECT_URI = 'com.epaflix.fytorium:/oauth2redirect';
const MAX_AGE_MS = 600_000;

export type LoginAttempt = { verifier: string; state: string; createdAt: number };

export function base64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function createAttempt(now = Date.now()): LoginAttempt {
  return { verifier: base64Url(Crypto.getRandomBytes(32)), state: base64Url(Crypto.getRandomBytes(32)), createdAt: now };
}

export async function challenge(verifier: string): Promise<string> {
  const ascii = Uint8Array.from(verifier, (char) => char.charCodeAt(0));
  return base64Url(new Uint8Array(await Crypto.digest(Crypto.CryptoDigestAlgorithm.SHA256, ascii)));
}

function sameText(a: string, b: string): boolean {
  let difference = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i += 1) difference |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return difference === 0;
}

const decode = (value: string) => decodeURIComponent(value.replace(/\+/g, ' '));

// Only a hostless redirect to this exact path, inside ten minutes, with one of each parameter and this attempt's state, counts.
function params(attempt: LoginAttempt, redirect: string, now: number): Map<string, string> | null {
  const age = now - attempt.createdAt;
  if (age < 0 || age > MAX_AGE_MS) return null;
  const prefix = 'com.epaflix.fytorium:';
  if (!redirect.startsWith(prefix)) return null;
  const rest = redirect.slice(prefix.length);
  if (rest.startsWith('//') || rest.includes('#')) return null;
  const mark = rest.indexOf('?');
  if ((mark < 0 ? rest : rest.slice(0, mark)) !== '/oauth2redirect') return null;
  const pairs: [string, string][] = [];
  try {
    for (const part of (mark < 0 ? '' : rest.slice(mark + 1)).split('&')) {
      const split = part.indexOf('=');
      pairs.push(split < 0 ? [decode(part), ''] : [decode(part.slice(0, split)), decode(part.slice(split + 1))]);
    }
  } catch {
    return null;
  }
  const result = new Map(pairs);
  if (result.size !== pairs.length) return null;
  if (!sameText(attempt.state, result.get('state') ?? '')) return null;
  return result;
}

export function codeFrom(attempt: LoginAttempt, redirect: string, now: number): string | null {
  const found = params(attempt, redirect, now);
  if (!found || found.has('error')) return null;
  const code = found.get('code');
  return code && code.trim() && code.length <= 1024 ? code : null;
}

// The server's reason for a refused login, from a redirect that belongs to this attempt.
export function errorFrom(attempt: LoginAttempt, redirect: string, now: number): string | null {
  return params(attempt, redirect, now)?.get('error') ?? null;
}
