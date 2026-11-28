import { createContext, useContext, useState, useSyncExternalStore } from 'react';
import { useRoute } from '@react-navigation/native';
import type { Repository, CareState } from '../data/repository';
import type { PhotoCache } from '../data/photos';
import type { Drafts } from '../data/drafts';
import { memberKey } from '../data/cache';
import { pageKey, type TabName } from './routes';

export type AppDeps = {
  care: Repository;
  photos: PhotoCache;
  drafts: Drafts;
  appVersion: string;
  serverVersion: string | null;
  online: boolean;
  busy: boolean;
  logout(): void;
  copyDiagnostics(): void;
  // W6-P9: keeps the task when Back at Today's root would otherwise drop a waiting draft.
  moveTaskToBack(): void;
  // W6-P11: tells Android whether Back goes to the app.
  setBackHandled(value: boolean): void;
};

// The tab whose stack a page is in, so a question can lead back to it (W6-P26).
export const TabContext = createContext<TabName>('Today');
export const useTab = () => useContext(TabContext);
// Whether that tab is the one shown: questions render above every tab, so a page's question shows only in its tab.
// A page covered in its own stack still asks, since what it blocks (a reselect, a notification tap) acts there.
export const TabShownContext = createContext(true);
export const useTabShown = () => useContext(TabShownContext);

const DepsContext = createContext<AppDeps | null>(null);
export const DepsProvider = DepsContext.Provider;

export function useDeps(): AppDeps {
  const deps = useContext(DepsContext);
  if (!deps) throw new Error('No app dependencies');
  return deps;
}

export function useCare(): CareState {
  const { care } = useDeps();
  return useSyncExternalStore(care.subscribe, care.get);
}

export function useMemberKey(): string {
  const member = useCare().member;
  return member ? memberKey(member) : 'none';
}

// Page state (a query, a scroll position, a typed draft) that survives Android ending the process or recreating the
// activity. It is stored under the page's route, and the shell removes it when that route leaves its stack (W6-P4).
export function useDraft<T>(name: string, initial: T): [T, (value: T) => void] {
  const { drafts } = useDeps();
  const key = pageKey(useMemberKey(), useRoute().key, name);
  const [value, setValue] = useState<T>(() => drafts.get<T>(key) ?? initial);
  return [value, (next: T) => { setValue(next); drafts.put(key, next); }];
}

export type Plant = { id: string; name: string; coverPhotoId: string | null };

// The few plant fields the W3 pages need; the plant waves read the rest.
export function parsePlants(json: string | null): Plant[] {
  if (!json) return [];
  try {
    const rows = JSON.parse(json);
    return Array.isArray(rows)
      ? rows.map((row) => ({ id: String(row.id), name: String(row.display_name ?? row.nickname ?? row.canonical_name ?? 'Plant'), coverPhotoId: row.cover_photo_id ?? null }))
      : [];
  } catch {
    return [];
  }
}

