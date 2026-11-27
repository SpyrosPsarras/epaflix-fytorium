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

// A 200 whose body is not the shape the lists need; it must not replace the last good copy (Kotlin parses first too).
class BadReply extends Error {}

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const text = (value: unknown) => typeof value === 'string';
const absent = (value: unknown) => value === null || value === undefined;
const optional = (check: (value: unknown) => boolean) => (value: unknown) => absent(value) || check(value);
const number = (value: unknown) => typeof value === 'number';
const objects = (check: (row: Record<string, unknown>) => boolean) => (value: unknown) => Array.isArray(value) && value.every((row) => isObject(row) && check(row));

function validZone(zone: unknown): boolean {
  if (!text(zone)) return false;
  try { new Intl.DateTimeFormat('en', { timeZone: zone as string }); return true; } catch { return false; }
}

// The fields Kotlin's parsers require before a reply is saved (CareModels.kt parseEvent, parseTask, parseTaskList,
// parsePlant, parseTimezone); the pages parse the rest when they arrive.
const isEvent = (event: Record<string, unknown>) => text(event.id) && text(event.plant_id) && text(event.kind) && text(event.occurred_on)
  && typeof event.can_undo === 'boolean' && optional(number)(event.batch_plant_count) && optional(number)(event.batch_event_count);
const isTask = (task: Record<string, unknown>) => text(task.status) && optional(number)(task.intervalDays)
  && objects((basis) => text(basis.input) && text(basis.source) && text(basis.adjustment) && optional(number)(basis.factor))(task.basedOn)
  && optional((event) => isObject(event) && isEvent(event))(task.completed_event);
const SHAPES = {
  tasks: (body: unknown) => isObject(body) && text(body.today) && text(body.view)
    && isObject(body.settings) && text(body.settings.sort) && text(body.settings.filter) && typeof body.settings.show_completed === 'boolean'
    && isObject(body.groups) && Object.values(body.groups).every(objects(isTask))
    && objects((section) => text(section.key) && text(section.label) && objects(isTask)(section.tasks))(body.sections),
  plants: objects((plant) => text(plant.id) && text(plant.display_name) && text(plant.scientific_name) && text(plant.status)
    && optional(number)(plant.pot_size_cm)),
  meta: (body: unknown) => isObject(body) && validZone(body.timezone),
};
const isPlant = (id: string) => (body: unknown) => SHAPES.plants([body]) && (body as { id: unknown }).id === id;

function checked(json: string, shape: (body: unknown) => boolean): string {
  let body: unknown;
  try { body = JSON.parse(json); } catch { throw new BadReply('Fytorium sent a reply this app cannot read.'); }
  if (!shape(body)) throw new BadReply('Fytorium sent a reply this app cannot read.');
  return json;
}

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
    const unavailable = !offline && (error instanceof NetworkError || error instanceof BadReply || (status !== null && status >= 500));
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
      let member: Member;
      try { member = parseMember(identity); } catch { throw new BadReply('Fytorium sent a reply this app cannot read.'); }
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
      checked(today, SHAPES.tasks); checked(upcoming, SHAPES.tasks); checked(plants, SHAPES.plants); checked(meta, SHAPES.meta);
      deps.cache.save(member, { today, upcoming, plants, meta }, now());
      loadCached();
      // The data is new, but a connection lost while it was on its way still shows as Offline.
      set({ offline: !deps.online(), serverUnavailable: false, message: null });
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
          const json = checked(await read(`/api/plants/${id}`), isPlant(id));
          if (epoch === generation) deps.cache.save(member, { [resource]: json }, now());
        } catch (error) {
          failed(error, epoch);
        }
      }
      return epoch === generation && sameMember(state.member, member) ? deps.cache.get(member, resource) : null;
    },
    wipe() {
      // A refresh asked for during the one in flight belongs to the session that just ended.
      again = false;
      generation += 1;
      deps.cache.wipe();
      deps.onMemberChanged(null);
      state = empty(!deps.online());
      listeners.forEach((listener) => listener());
    },
  };
}

export type Repository = ReturnType<typeof createRepository>;
