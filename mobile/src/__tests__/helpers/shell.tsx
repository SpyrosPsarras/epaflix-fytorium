import { useSyncExternalStore } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { CommonActions, DefaultTheme, type NavigationState } from '@react-navigation/native';
import { PaperProvider } from 'react-native-paper';
import type { RawResponse } from '../../../modules/fytorium-native';
import { CareCache, type Member } from '../../data/cache';
import { createRepository } from '../../data/repository';
import type { PhotoCache } from '../../data/photos';
import { Drafts } from '../../data/drafts';
import { DepsProvider } from '../../shell/context';
import { ShellProvider, useShell } from '../../shell/guards';
import { Shell, navigation } from '../../shell/Shell';
import { nodeDb } from './nodeDb';

export const owner: Member = { id: 'm1', role: 'owner' };
export const plantRow = (id: string, name: string, cover: string | null = null) => ({ id, display_name: name, scientific_name: 'Testoria lucida', status: 'active', cover_photo_id: cover });

export async function setup(member: Member = owner) {
  const db = nodeDb();
  const cache = new CareCache(db);
  let identity = member;
  let plants = [plantRow('p1', 'Synthetic fern'), plantRow('p2', 'Synthetic thyme')];
  const failing = new Set<string>();
  const api = {
    request: async (path: string): Promise<RawResponse> => {
      const body: Record<string, string> = {
        '/api/me': JSON.stringify({ member: { ...identity, display_name: 'Synthetic member' } }),
        '/api/plants': JSON.stringify(plants),
        ...Object.fromEntries(plants.map((plant) => [`/api/plants/${plant.id}`, JSON.stringify(plant)])),
        '/api/tasks': JSON.stringify({ today: '2026-11-27', view: 'today', settings: { sort: 'task', filter: 'all', show_completed: false }, groups: {}, sections: [] }),
        '/api/meta': JSON.stringify({ timezone: 'Europe/Athens' }),
      };
      if (failing.has(path)) return { status: 503, headers: {}, body: '' };
      return { status: body[path] !== undefined ? 200 : 404, headers: {}, body: body[path] ?? '{}' };
    },
  };
  const drafts = new Drafts(db);
  let moved = 0;
  let handlesBack: boolean | null = null;
  // The production wiring: a member or role change in the repository wipes drafts (services.ts).
  const care = createRepository({ api: api as never, cache, online: () => true, onMemberChanged: () => drafts.wipe() });
  await care.refresh();
  // Every cover loads as a file named after its id, so a test can see which cover shows.
  const photos = { load: async (_member: Member, id: string) => `file:///photos/${id}.jpg` } as unknown as PhotoCache;
  const states: (NavigationState | undefined)[] = [];
  const homes = new Set<() => void>();
  let shell: ReturnType<typeof useShell> | null = null;
  function Capture() { shell = useShell(); return null; }
  const logout = jest.fn();
  const deps = { care, photos, drafts, appVersion: '2.0.0', serverVersion: null, online: true, busy: false, logout, copyDiagnostics: jest.fn(), moveTaskToBack: () => { moved += 1; },
    setBackHandled: (value: boolean) => { handlesBack = value; } };
  function Host({ initialState }: { initialState?: NavigationState }) {
    const state = useSyncExternalStore(care.subscribe, care.get);
    return (
      <Shell member={state.member} theme={DefaultTheme} initialState={initialState} onState={(next) => states.push(next)}
        subscribeOpenHome={(listener) => { homes.add(listener); return () => homes.delete(listener); }} />
    );
  }
  const tree = (initialState?: NavigationState) => (
    <PaperProvider>
      <DepsProvider value={deps}>
        <ShellProvider>
          <Capture />
          <Host initialState={initialState} />
        </ShellProvider>
      </DepsProvider>
    </PaperProvider>
  );
  const view = await render(tree());
  return {
    view, db, drafts, states, tree, logout, moved: () => moved, handlesBack: () => handlesBack,
    addPlant: (id: string, name: string) => { plants = [...plants, plantRow(id, name)]; },
    renamePlant: (id: string, name: string, cover: string | null = null) => { plants = plants.map((plant) => (plant.id === id ? plantRow(id, name, cover) : plant)); },
    fail: (path: string, on: boolean) => { if (on) failing.add(path); else failing.delete(path); },
    care,
    shell: () => shell!,
    openHome: () => act(() => homes.forEach((listener) => listener())),
    becomes: async (next: Member) => {
      identity = next;
      await act(async () => { await care.refresh(); });
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    },
  };
}

export const tab = (name: string) => screen.getByTestId(`tab-${name}`);
export const selected = (name: string) => tab(name).props.accessibilityState?.selected === true;
// Presses settle every navigation update and dialog animation before the next step.
export const press = async (element: Parameters<typeof fireEvent.press>[0]) => {
  await fireEvent.press(element);
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
};
export const back = () => press(screen.getAllByLabelText('Back').at(-1)!);
export const systemBack = () => act(() => { navigation.dispatch(CommonActions.goBack()); });

