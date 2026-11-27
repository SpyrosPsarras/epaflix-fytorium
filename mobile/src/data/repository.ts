import type { Api } from '../api/client';
import { NetworkError } from '../api/client';
import { CareCache, parseMember, sameMember, type Member } from './cache';

export type CareState = {
  member: Member | null;
  // Raw server snapshots; the screens of later waves parse what they show.
  today: string | null;
  upcoming: string | null;
  plants: string | null;
  meta: string | null;
  identity: string | null;
  lastUpdated: number | null;
  offline: boolean;
  serverUnavailable: boolean;
  busy: boolean;
  message: string | null;
};

const empty = (offline: boolean): CareState => ({
  member: null, today: null, upcoming: null, plants: null, meta: null, identity: null, lastUpdated: null,
  offline, serverUnavailable: false, busy: false, message: null,
});

class ApiFailure extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

type Deps = {
  api: Pick<Api, 'request'>;
  cache: CareCache;
  // A validated network: the phone can reach the internet, which says nothing about Fytorium.
  online(): boolean;
  // Runs whenever the bound member or role changes, before anything is read for the new one.
  onMemberChanged(member: Member | null): void;
  now?: () => number;
};

export const LISTS = ['today', 'upcoming', 'plants', 'meta'] as const;

// One generation per binding: any read that started before a wipe or a member change is ignored when it returns.
export function createRepository(deps: Deps) {
  const now = deps.now ?? Date.now;
  let state = empty(!deps.online());
  let generation = 0;
  let running: Promise<void> | null = null;
  let again = false;
  const listeners = new Set<() => void>();

  function set(patch: Partial<CareState>) {
    state = { ...state, ...patch };
    listeners.forEach((listener) => listener());
  }

  async function read(path: string, query?: Record<string, string>): Promise<string> {
    const response = await deps.api.request(path, { query });
    if (response.status < 200 || response.status > 299) {
      let message = `Fytorium answered ${response.status}.`;
      try { message = JSON.parse(response.body)?.message ?? JSON.parse(response.body)?.error ?? message; } catch { /* keep the status */ }
      throw new ApiFailure(response.status, message);
    }
    return response.body;
  }

  function loadCached() {
    const member = deps.cache.member();
    if (!member) return;
    const rows = Object.fromEntries(LISTS.map((name) => [name, deps.cache.get(member, name)]));
    set({
      member, identity: deps.cache.identity(),
      today: rows.today?.json ?? null, upcoming: rows.upcoming?.json ?? null, plants: rows.plants?.json ?? null, meta: rows.meta?.json ?? null,
      lastUpdated: rows.today?.refreshedAt ?? null,
    });
  }

  function failed(error: unknown, epoch: number) {
    if (epoch !== generation) return;
    // Offline when the phone has no validated network; otherwise a failed request or a 5xx means Fytorium isn't responding.
    const offline = !deps.online();
    const status = error instanceof ApiFailure ? error.status : null;
    const unavailable = !offline && (error instanceof NetworkError || (status !== null && status >= 500));
    set({
      offline: offline || unavailable ? offline : state.offline,
      serverUnavailable: offline || unavailable ? unavailable : state.serverUnavailable,
      message: status !== null && status < 500 ? (error as Error).message : null,
    });
  }

  async function refreshOnce() {
    loadCached();
    let epoch = generation;
    set({ busy: true, message: null });
    try {
      if (!deps.online()) throw new NetworkError('Offline');
      const identity = await read('/api/me');
      const member = parseMember(identity);
      if (epoch !== generation) return;
      if (deps.cache.bind(identity)) {
        // A new binding ends every read started for the one before; only this refresh carries on under the new one.
        generation += 1;
        epoch = generation;
        deps.onMemberChanged(member);
        set({ ...empty(false), member, identity, busy: true });
      }
      const [today, upcoming, plants, meta] = await Promise.all([
        read('/api/tasks', { view: 'today' }), read('/api/tasks', { view: 'upcoming' }), read('/api/plants'), read('/api/meta'),
      ]);
      if (epoch !== generation) return;
      deps.cache.save(member, { today, upcoming, plants, meta }, now());
      loadCached();
      set({ offline: false, serverUnavailable: false, message: null });
    } catch (error) {
      failed(error, epoch);
    } finally {
      if (epoch === generation) set({ busy: false });
    }
  }

  return {
    get: () => state,
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    restore: loadCached,
    // One refresh at a time; a request during a refresh runs once more after it, so the last answer is the newest.
    refresh(): Promise<void> {
      if (running) { again = true; return running; }
      running = (async () => {
        do { again = false; await refreshOnce(); } while (again);
      })().finally(() => { running = null; });
      return running;
    },
    connectivityChanged(online: boolean) {
      // A connected network does not prove Fytorium answers; only a refresh clears Offline.
      if (!online) set({ offline: true, serverUnavailable: false });
    },
    // A plant page's read, kept per member so it shows offline.
    async plant(id: string): Promise<{ json: string; refreshedAt: number } | null> {
      if (!/^[A-Za-z0-9_-]+$/.test(id)) throw new Error('Invalid plant');
      const member = state.member;
      if (!member) return null;
      const epoch = generation;
      const resource = `plant/${id}`;
      if (deps.online()) {
        try {
          const json = await read(`/api/plants/${id}`);
          if (epoch === generation) deps.cache.save(member, { [resource]: json }, now());
        } catch (error) {
          failed(error, epoch);
        }
      }
      return epoch === generation && sameMember(state.member, member) ? deps.cache.get(member, resource) : null;
    },
    wipe() {
      generation += 1;
      deps.cache.wipe();
      deps.onMemberChanged(null);
      state = empty(!deps.online());
      listeners.forEach((listener) => listener());
    },
  };
}

export type Repository = ReturnType<typeof createRepository>;
