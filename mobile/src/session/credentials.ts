import * as SecureStore from 'expo-secure-store';
import type { LoginAttempt } from './pkce';

export type Tokens = { access: string; refresh: string };

// The token pair stays readable after the first unlock, so the iOS background refresh works on a locked phone.
const TOKENS = { key: 'fytorium.tokens', options: { keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY } };
const PENDING = { key: 'fytorium.pending', options: { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY } };

function read<T>(entry: typeof TOKENS): T | null {
  try {
    const text = SecureStore.getItem(entry.key, entry.options);
    return text ? (JSON.parse(text) as T) : null;
  } catch {
    return null;
  }
}

export class Credentials {
  private current: Tokens | null;
  private listeners = new Set<() => void>();
  // A new number for every login and every session end, so late results of an ended session are recognised.
  session = 0;
  refreshing: Promise<Tokens | null> | null = null;

  constructor() {
    this.current = read<Tokens>(TOKENS);
  }

  tokens(): Tokens | null {
    return this.current;
  }

  async setTokens(tokens: Tokens): Promise<void> {
    await SecureStore.setItemAsync(TOKENS.key, JSON.stringify(tokens), TOKENS.options);
    if (!this.current) this.session += 1;
    this.current = tokens;
    this.notify();
  }

  pending(): LoginAttempt | null {
    return read<LoginAttempt>(PENDING);
  }

  async setPending(attempt: LoginAttempt | null): Promise<void> {
    if (attempt) await SecureStore.setItemAsync(PENDING.key, JSON.stringify(attempt), PENDING.options);
    else await SecureStore.deleteItemAsync(PENDING.key, PENDING.options);
  }

  async clear(): Promise<void> {
    const had = this.current !== null;
    this.current = null;
    this.session += 1;
    await Promise.allSettled([SecureStore.deleteItemAsync(TOKENS.key, TOKENS.options), SecureStore.deleteItemAsync(PENDING.key, PENDING.options)]);
    if (had) this.notify();
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private notify() {
    this.listeners.forEach((listener) => listener());
  }
}
