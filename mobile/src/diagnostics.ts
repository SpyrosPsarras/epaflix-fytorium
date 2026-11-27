import Storage from 'expo-sqlite/kv-store';

// Only these event names may enter diagnostics: never exception text, URLs or response bodies.
export type DiagnosticEvent = 'NETWORK_UNAVAILABLE' | 'LOGIN_REJECTED' | 'SESSION_ENDED' | 'APP_CRASH';
const KEY = 'diagnostics.events';

export function record(event: DiagnosticEvent, now = Date.now()) {
  try {
    const lines = (Storage.getItemSync(KEY) ?? '').split('\n').filter(Boolean);
    Storage.setItemSync(KEY, [...lines, `${now} ${event}`].slice(-64).join('\n'));
  } catch {
    // Diagnostics must never break the action that recorded them.
  }
}

export function copyText(appVersion: string): string {
  return `Fytorium ${appVersion}\n` + (Storage.getItemSync(KEY) ?? '').slice(0, 8192);
}

export function clear() {
  try { Storage.removeItemSync(KEY); } catch { /* nothing to clear */ }
}
