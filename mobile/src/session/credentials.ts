import * as SecureStore from 'expo-secure-store';
import type { LoginAttempt } from './pkce';

export type Tokens = { access: string; refresh: string };
// The stored copy of an ended session could not be deleted; it is retried before any new login is kept.
export class WipeError extends Error {}
// A persistent "wipe still pending" marker: startup refuses stored credentials and retries the wipe while it is set.
export type WipeMarker = { get(): boolean; set(value: boolean): boolean };

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
  private current: Tokens | null = null;
  private listeners = new Set<() => void>();
  // SecureStore writes and deletes run one at a time, so a late write can never land after a wipe.
  private queue: Promise<unknown> = Promise.resolve();
  // A new number for every login and every session end, so late results of an ended session are recognised.
  session = 0;
  // A wipe this process started and has not finished, even if the marker could not be saved.
  private outstanding = false;
  // Whether a wipe marker may be on disk: this process confirmed writing one, or found one at start.
  // Android can show a failed write in memory, so only confirmed writes count.
  private markerSaved = false;
  refreshing: Promise<Tokens | null> | null = null;

  // Credentials are usable only after first-run cleanup succeeded and no earlier wipe is unfinished.
  constructor(private readonly usable: boolean, private readonly wipe: WipeMarker) {
    if (!usable) return;
    if (wipe.get()) {
      this.markerSaved = true;
      void this.clear().catch(() => undefined);
    }
    else this.current = read<Tokens>(TOKENS);
  }

  private serial<T>(task: () => Promise<T>): Promise<T> {
    const next = this.queue.then(task, task);
    this.queue = next.catch(() => undefined);
    return next;
  }

  tokens(): Tokens | null {
    return this.current;
  }

  private async deleteStored() {
    try {
      await SecureStore.deleteItemAsync(TOKENS.key, TOKENS.options);
      await SecureStore.deleteItemAsync(PENDING.key, PENDING.options);
    } catch {
      throw new WipeError("Fytorium couldn't clear the last login on this phone.");
    }
    // A marker that stays saved would delete the next login on restart, so it must be cleared before anything new.
    if (!this.wipe.set(false) && this.markerSaved) throw new WipeError("Fytorium couldn't clear the last login on this phone.");
    this.outstanding = false;
    this.markerSaved = false;
  }

  // Stored copies of an ended session go before anything new is written.
  private async finishWipe() {
    if (this.outstanding || this.wipe.get()) await this.deleteStored();
  }

  // Whether a restart would retry an unfinished wipe, because its marker reached storage.
  wipeRetried(): boolean {
    return this.markerSaved;
  }

  // Stores tokens unless the session changed since `session` was read; returns whether they were stored.
  setTokens(tokens: Tokens, session = this.session): Promise<boolean> {
    return this.serial(async () => {
      if (!this.usable || session !== this.session) return false;
      await this.finishWipe();
      await SecureStore.setItemAsync(TOKENS.key, JSON.stringify(tokens), TOKENS.options);
      // A logout during the write ended the session; its queued delete removes this copy.
      if (session !== this.session) return false;
      if (!this.current) this.session += 1;
      this.current = tokens;
      this.notify();
      return true;
    });
  }

  pending(): LoginAttempt | null {
    return this.usable && !this.outstanding && !this.wipe.get() ? read<LoginAttempt>(PENDING) : null;
  }

  setPending(attempt: LoginAttempt | null): Promise<void> {
    return this.serial(async () => {
      await this.finishWipe();
      if (attempt) await SecureStore.setItemAsync(PENDING.key, JSON.stringify(attempt), PENDING.options);
      else await SecureStore.deleteItemAsync(PENDING.key, PENDING.options);
    });
  }

  // Ends the session in memory at once; the stored copies are deleted in order and the marker stays until both are gone.
  clear(): Promise<void> {
    const had = this.current !== null;
    this.current = null;
    this.session += 1;
    this.outstanding = true;
    this.markerSaved = this.wipe.set(true) || this.markerSaved;
    if (had) this.notify();
    // The stored copies are deleted even when the marker could not be saved.
    return this.serial(() => this.deleteStored());
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private notify() {
    this.listeners.forEach((listener) => listener());
  }
}
