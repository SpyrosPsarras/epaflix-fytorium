import { error } from '@sveltejs/kit';

// shortcut: per-process limits suit one replica; use a shared store before scaling out.
const attempts = new Map<string, { count: number; expires: number }>();
export function limitAppTokens(kind: 'ip' | 'member' | 'device', id: string) {
  const time = performance.now();
  for (const [key, entry] of attempts) if (entry.expires <= time) attempts.delete(key);
  const key = kind + ':' + id;
  let entry = attempts.get(key);
  if (!entry) {
    // Fail closed rather than grow without bound under many source addresses.
    if (attempts.size >= 10000) error(429, 'Too many token attempts');
    entry = { count: 0, expires: time + 60000 };
    attempts.set(key, entry);
  }
  if (++entry.count > (kind === 'ip' ? 600 : kind === 'member' ? 30 : 10)) error(429, 'Too many token attempts');
}
