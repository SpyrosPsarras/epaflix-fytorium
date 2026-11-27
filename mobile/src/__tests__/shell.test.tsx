import { useSyncExternalStore } from 'react';
import { BackHandler } from 'react-native';
import { act, fireEvent, render, screen, within } from '@testing-library/react-native';
import { CommonActions, DefaultTheme, type NavigationState } from '@react-navigation/native';
import { PaperProvider } from 'react-native-paper';
import type { RawResponse } from '../../modules/fytorium-native';
import { CareCache, type Member } from '../data/cache';
import { createRepository } from '../data/repository';
import { PhotoCache } from '../data/photos';
import { Drafts } from '../data/drafts';
import { DepsProvider } from '../shell/context';
import { ShellProvider, useShell } from '../shell/guards';
import { Shell, navigation } from '../shell/Shell';
import { nodeDb } from './helpers/nodeDb';

jest.mock('expo-image', () => ({ Image: () => null }));

const owner: Member = { id: 'm1', role: 'owner' };
const plantRow = (id: string, name: string) => ({ id, display_name: name, scientific_name: 'Testoria lucida', status: 'active', cover_photo_id: null });

async function setup(member: Member = owner) {
  const db = nodeDb();
  const cache = new CareCache(db);
  let identity = member;
  let plants = [plantRow('p1', 'Synthetic fern'), plantRow('p2', 'Synthetic thyme')];
  const api = {
    request: async (path: string): Promise<RawResponse> => {
      const body: Record<string, string> = {
        '/api/me': JSON.stringify({ member: { ...identity, display_name: 'Synthetic member' } }),
        '/api/plants': JSON.stringify(plants),
        '/api/plants/p1': JSON.stringify({ id: 'p1', display_name: 'Synthetic fern' }),
        '/api/tasks': JSON.stringify({ today: '2026-11-27', view: 'today', settings: { sort: 'task', filter: 'all', show_completed: false }, groups: {}, sections: [] }),
        '/api/meta': JSON.stringify({ timezone: 'Europe/Athens' }),
      };
      return { status: body[path] !== undefined ? 200 : 404, headers: {}, body: body[path] ?? '{}' };
    },
  };
  const drafts = new Drafts(db);
  let moved = 0;
  // The production wiring: a member or role change in the repository wipes drafts (services.ts).
  const care = createRepository({ api: api as never, cache, online: () => true, onMemberChanged: () => drafts.wipe() });
  await care.refresh();
  const photos = new PhotoCache(db, { uri: () => null, write: async () => undefined, remove: () => undefined, removeAll: () => undefined }, api as never, () => false, () => care.get().member);
  const states: (NavigationState | undefined)[] = [];
  const homes = new Set<() => void>();
  let shell: ReturnType<typeof useShell> | null = null;
  function Capture() { shell = useShell(); return null; }
  const logout = jest.fn();
  const deps = { care, photos, drafts, appVersion: '2.0.0', serverVersion: null, online: true, busy: false, logout, copyDiagnostics: jest.fn(), moveTaskToBack: () => { moved += 1; } };
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
    view, db, drafts, states, tree, logout, moved: () => moved,
    addPlant: (id: string, name: string) => { plants = [...plants, plantRow(id, name)]; },
    shell: () => shell!,
    openHome: () => act(() => homes.forEach((listener) => listener())),
    becomes: async (next: Member) => {
      identity = next;
      await act(async () => { await care.refresh(); });
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    },
  };
}

const tab = (name: string) => screen.getByTestId(`tab-${name}`);
const selected = (name: string) => tab(name).props.accessibilityState?.selected === true;
// Presses settle every navigation update and dialog animation before the next step.
const press = async (element: Parameters<typeof fireEvent.press>[0]) => {
  await fireEvent.press(element);
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
};
const back = () => press(screen.getAllByLabelText('Back').at(-1)!);
const systemBack = () => act(() => { navigation.dispatch(CommonActions.goBack()); });

test('W6-P5, P1: a tab tap never closes a page, and Back returns to the grid with its search', async () => {
  await setup();
  await press(tab('Plants'));
  await fireEvent.changeText(screen.getByTestId('plants-search'), 'fern');
  expect(screen.queryByText('Synthetic thyme')).toBeNull();
  await press(screen.getByText('Synthetic fern'));
  expect(screen.getByTestId('plant-p1')).toBeTruthy();
  await press(tab('Today'));
  await press(tab('Plants'));
  expect(screen.getByTestId('plant-p1')).toBeTruthy();
  await back();
  expect(screen.getByTestId('plants-search').props.value).toBe('fern');
});

test('W6-P7, P12: a plant opened from Today keeps Today selected and Back returns to Today', async () => {
  await setup();
  await press(screen.getByText('Synthetic fern'));
  expect(screen.getByTestId('plant-p1')).toBeTruthy();
  expect(selected('Today')).toBe(true);
  expect(selected('Plants')).toBe(false);
  await systemBack();
  expect(screen.getByTestId('today-root')).toBeTruthy();
});

test('W6-P12: Find a plant keeps its query under a plant page; Journal and More pages return to their opener', async () => {
  await setup();
  await press(screen.getByText('Find a plant'));
  await fireEvent.changeText(screen.getByTestId('find-search'), 'thyme');
  await press(screen.getByText('Synthetic thyme'));
  await back();
  expect(screen.getByTestId('find-search').props.value).toBe('thyme');
  await back();
  expect(screen.getByTestId('today-root')).toBeTruthy();

  await press(tab('Journal'));
  await press(screen.getAllByText('Synthetic fern').at(-1)!);
  expect(selected('Journal')).toBe(true);
  await back();
  expect(screen.getByTestId('journal-root')).toBeTruthy();

  await press(tab('More'));
  await press(screen.getByText('About'));
  expect(screen.getByText('Copy diagnostics')).toBeTruthy();
  await back();
  expect(screen.getByTestId('more-root')).toBeTruthy();
});

test('W6-P9: Back at the Plants, Journal and More roots shows Today as it was left', async () => {
  await setup();
  for (const name of ['Plants', 'Journal', 'More']) {
    await press(tab(name));
    await systemBack();
    expect(selected('Today')).toBe(true);
  }
});

test('W6-P6: reselecting a tab returns to its root, asking first about a form with changes', async () => {
  await setup();
  await press(tab('Plants'));
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await press(tab('Today'));
  expect(screen.queryByText('Discard changes?')).toBeNull();
  await press(tab('Plants'));
  expect(screen.getByTestId('draft-field').props.value).toBe('Monty');
  await press(tab('Plants'));
  expect(await screen.findByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Keep editing'));
  expect(screen.getByTestId('draft-field').props.value).toBe('Monty');
  await press(tab('Plants'));
  await press(await screen.findByText('Discard'));
  expect(screen.getByTestId('plants-root')).toBeTruthy();
});

test('W6-P16: a notification tap shows Today at its root and leaves other tabs alone', async () => {
  const h = await setup();
  await press(tab('Journal'));
  await press(screen.getByText('Add a photo'));
  await press(screen.getAllByText('Synthetic fern').at(-1)!);
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'New leaf');
  await press(tab('Today'));
  await press(screen.getByText('Synthetic thyme'));
  await h.openHome();
  expect(selected('Today')).toBe(true);
  expect(screen.getByTestId('today-root')).toBeTruthy();
  expect(screen.queryByText('Discard changes?')).toBeNull();
  await press(tab('Journal'));
  expect(screen.getByTestId('draft-field').props.value).toBe('New leaf');
});

test('W6-P17: a role change closes what the new role may not open; a new member starts at Today', async () => {
  const h = await setup();
  await press(screen.getByText('Import from Planta'));
  await press(tab('More'));
  await press(screen.getByText('Members'));
  await h.becomes({ id: 'm1', role: 'family' });
  await press(tab('Today'));
  expect(screen.getByTestId('today-root')).toBeTruthy();
  await press(tab('More'));
  expect(screen.getByTestId('more-root')).toBeTruthy();
  await press(screen.getByText('Notifications'));
  await h.becomes({ id: 'm1', role: 'sitter' });
  expect(screen.queryByTestId('tab-Journal')).toBeNull();
  expect(selected('More')).toBe(true);
  expect(screen.getByText('This page arrives in a later update.')).toBeTruthy();
  await h.becomes({ id: 'm2', role: 'owner' });
  expect(selected('Today')).toBe(true);
  await press(tab('More'));
  expect(screen.getByTestId('more-root')).toBeTruthy();
});

test('W6-P17: a form with changes cannot stop a role change, and no page keeps state from before it', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await fireEvent.changeText(screen.getByTestId('plants-search'), 'fern');
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await h.becomes({ id: 'm1', role: 'family' });
  expect(screen.queryByText('Discard changes?')).toBeNull();
  expect(screen.getByTestId('draft-field').props.value).toBe('');
  await press(tab('Plants'));
  await press(tab('Plants'));
  expect(screen.getByTestId('plants-search').props.value).toBe('');
  expect(h.db.all<{ json: string }>('SELECT json FROM drafts').map((row) => row.json)).not.toContain('"Monty"');
  expect(h.db.all<{ json: string }>('SELECT json FROM drafts').map((row) => row.json)).not.toContain('"fern"');
});

test('W6-P21: a tab tap, a reselect and a notification tap during a save happen when it ends', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await act(async () => h.shell().setSaving('plant-edit', true));
  await press(tab('More'));
  expect(selected('Plants')).toBe(true);
  await act(async () => h.shell().setSaving('plant-edit', false));
  expect(selected('More')).toBe(true);

  await press(screen.getByText('About'));
  await act(async () => h.shell().setSaving('about', true));
  await press(tab('More'));
  expect(screen.getByText('Copy diagnostics')).toBeTruthy();
  await act(async () => h.shell().setSaving('about', false));
  expect(screen.getByTestId('more-root')).toBeTruthy();

  await press(tab('Today'));
  await press(screen.getByText('Synthetic thyme'));
  await press(tab('More'));
  await act(async () => h.shell().setSaving('more', true));
  await h.openHome();
  expect(selected('More')).toBe(true);
  await act(async () => h.shell().setSaving('more', false));
  expect(selected('Today')).toBe(true);
  expect(screen.getByTestId('today-root')).toBeTruthy();
});

test('W6-P26: log out asks about a form with changes left open in another tab', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await press(tab('More'));
  await press(screen.getByText('Log out'));
  await press(screen.getByTestId('logout-confirm'));
  expect(await screen.findByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Keep editing'));
  expect(selected('Plants')).toBe(true);
  expect(screen.getByTestId('draft-field').props.value).toBe('Monty');
  expect(h.logout).not.toHaveBeenCalled();
  await press(tab('More'));
  await press(screen.getByText('Log out'));
  await press(screen.getByTestId('logout-confirm'));
  await press(await screen.findByText('Discard'));
  expect(h.logout).toHaveBeenCalledTimes(1);
});

test('W6-P9: Back at Today keeps the task while a draft waits in another tab, and leaves normally without one', async () => {
  const handlers: (() => boolean)[] = [];
  jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_event, handler) => {
    handlers.push(handler as () => boolean);
    return { remove: () => handlers.splice(handlers.indexOf(handler as () => boolean), 1) };
  });
  const h = await setup();
  const back = () => handlers.at(-1)!();
  expect(back()).toBe(false);
  await press(tab('Plants'));
  await fireEvent.changeText(screen.getByTestId('plants-search'), 'fern');
  await press(tab('Today'));
  expect(back()).toBe(false);
  await press(tab('Journal'));
  await press(screen.getByText('Add a photo'));
  await press(screen.getAllByText('Synthetic fern').at(-1)!);
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'New leaf');
  await press(tab('Today'));
  expect(back()).toBe(true);
  expect(h.moved()).toBe(1);
  jest.restoreAllMocks();
});

test('W6-P4, N3: every tab pages, drafts, queries and scroll come back after Android recreated the activity', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await press(tab('Today'));
  await press(within(screen.getByTestId('today-root')).getByText('Find a plant'));
  await fireEvent.changeText(screen.getByTestId('find-search'), 'thyme');
  await press(screen.getAllByText('Synthetic thyme').at(-1)!);
  await press(tab('Journal'));
  await fireEvent(within(screen.getByTestId('journal-root')).getByTestId('plant-rows'), 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 0, y: 120 } } });
  await press(tab('More'));
  const saved = h.states.at(-1)!;
  // The same process: React unmounts the old activity's tree, and nothing it runs may drop a draft.
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  expect(selected('More')).toBe(true);
  await press(screen.getByText('Log out'));
  await press(screen.getByTestId('logout-confirm'));
  expect(await screen.findByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Keep editing'));
  expect(selected('Plants')).toBe(true);
  expect(screen.getByTestId('draft-field').props.value).toBe('Monty');
  expect(h.logout).not.toHaveBeenCalled();
  await press(tab('Today'));
  expect(screen.getByTestId('plant-p2')).toBeTruthy();
  await systemBack();
  expect(screen.getByTestId('find-search').props.value).toBe('thyme');
  await press(tab('Journal'));
  expect(within(screen.getByTestId('journal-root')).getByTestId('plant-rows').props.contentOffset).toEqual({ x: 0, y: 120 });
  await restored.unmount();
});

test('W6-P4: a page saved state goes with its route, so Find opens empty again', async () => {
  const h = await setup();
  await press(screen.getByText('Find a plant'));
  await fireEvent.changeText(screen.getByTestId('find-search'), 'thyme');
  await back();
  expect(h.db.all('SELECT key FROM drafts')).toEqual([]);
  await press(screen.getByText('Find a plant'));
  expect(screen.getByTestId('find-search').props.value).toBe('');
});

test('W6-P26: log out asks about each form with changes in turn', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await press(tab('Journal'));
  await press(screen.getByText('Add a photo'));
  await press(screen.getAllByText('Synthetic fern').at(-1)!);
  await fireEvent.changeText(screen.getAllByTestId('draft-field').at(-1)!, 'New leaf');
  await press(tab('More'));
  await press(screen.getByText('Log out'));
  await press(screen.getByTestId('logout-confirm'));
  await press(await screen.findByText('Discard'));
  expect(h.logout).not.toHaveBeenCalled();
  expect(screen.getByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Keep editing'));
  expect(selected('Journal')).toBe(true);
  expect(h.logout).not.toHaveBeenCalled();
  await press(tab('More'));
  await press(screen.getByText('Log out'));
  await press(screen.getByTestId('logout-confirm'));
  await press(await screen.findByText('Discard'));
  await press(await screen.findByText('Discard'));
  expect(h.logout).toHaveBeenCalledTimes(1);
});

test('W6-P12: Back from each page goes to the page or tab that opened it', async () => {
  await setup();
  const today = () => within(screen.getByTestId('today-root'));
  const more = () => within(screen.getByTestId('more-root'));
  await press(today().getByText('Weather and feed'));
  await systemBack();
  expect(selected('Today')).toBe(true);
  expect(screen.getByTestId('today-root')).toBeTruthy();
  await press(today().getByText('Import from Planta'));
  await systemBack();
  expect(screen.getByTestId('today-root')).toBeTruthy();

  await press(tab('More'));
  await press(more().getByText('Weather and feed'));
  expect(selected('More')).toBe(true);
  await systemBack();
  expect(screen.getByTestId('more-root')).toBeTruthy();
  await press(more().getByText('Import from Planta'));
  await press(screen.getByText('Open imported plant'));
  expect(selected('More')).toBe(true);
  await systemBack();
  await press(screen.getByText('Check sites'));
  await systemBack();
  expect(screen.getByText('Open imported plant')).toBeTruthy();
  await systemBack();
  await press(more().getByText('Species without care data'));
  await press(screen.getByText('Synthetic species'));
  await systemBack();
  expect(screen.getByText('Synthetic species')).toBeTruthy();
  await systemBack();
  expect(screen.getByTestId('more-root')).toBeTruthy();

  await press(tab('Journal'));
  await press(screen.getByText('Add a photo'));
  await press(screen.getAllByText('Synthetic fern').at(-1)!);
  expect(screen.getByTestId('draft-field')).toBeTruthy();
  await systemBack();
  expect(screen.queryByTestId('draft-field')).toBeNull();
  expect(screen.getAllByText('Choose a plant').length).toBeGreaterThan(0);
  await systemBack();
  expect(screen.getByTestId('journal-root')).toBeTruthy();
});

test('W6-P17: losing a page removes every page opened from it; becoming a sitter closes plant pages and Find too', async () => {
  const h = await setup();
  await press(tab('More'));
  await press(within(screen.getByTestId('more-root')).getByText('Import from Planta'));
  await press(screen.getByText('Check sites'));
  await h.becomes({ id: 'm1', role: 'family' });
  expect(selected('More')).toBe(true);
  expect(screen.getByTestId('more-root')).toBeTruthy();
  expect(screen.queryByText('Check sites')).toBeNull();
  expect(screen.queryByLabelText('Back')).toBeNull();

  await press(tab('Today'));
  await press(within(screen.getByTestId('today-root')).getByText('Find a plant'));
  await press(screen.getAllByText('Synthetic fern').at(-1)!);
  await h.becomes({ id: 'm1', role: 'sitter' });
  expect(screen.getByTestId('today-root')).toBeTruthy();
  expect(screen.queryByTestId('plant-p1')).toBeNull();
  expect(screen.queryByLabelText('Back')).toBeNull();
  await press(within(screen.getByTestId('today-root')).getByText('Find a plant'));
  await press(screen.getAllByText('Synthetic fern').at(-1)!);
  expect(screen.getByTestId('plant-p1')).toBeTruthy();
});

test('N3: the first screen is saved before anything opens, so its scroll comes back', async () => {
  const h = await setup();
  await fireEvent(within(screen.getByTestId('today-root')).getByTestId('plant-rows'), 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 0, y: 80 } } });
  const saved = h.states.at(-1);
  expect(saved).toBeDefined();
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  expect(within(screen.getByTestId('today-root')).getByTestId('plant-rows').props.contentOffset).toEqual({ x: 0, y: 80 });
  await restored.unmount();
});

test('W6-P4: the log out question is still open after Android recreated the activity', async () => {
  const h = await setup();
  await press(tab('More'));
  await press(screen.getByText('Log out'));
  expect(screen.getByTestId('logout-confirm')).toBeTruthy();
  const saved = h.states.at(-1)!;
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  expect(screen.getByTestId('logout-confirm')).toBeTruthy();
  await restored.unmount();
});

test('W6-P6: reselecting a tab at its root scrolls to the top and keeps that, also when it waited for a save', async () => {
  const h = await setup();
  const rows = () => within(screen.getByTestId('plants-root')).getByTestId('plant-rows');
  await press(tab('Plants'));
  await fireEvent(rows(), 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 0, y: 200 } } });
  await press(tab('Plants'));
  expect(rows().props.contentOffset).toEqual({ x: 0, y: 0 });
  await fireEvent(rows(), 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 0, y: 150 } } });
  await act(async () => h.shell().setSaving('plants', true));
  await press(tab('Plants'));
  expect(rows().props.contentOffset).toEqual({ x: 0, y: 150 });
  await act(async () => h.shell().setSaving('plants', false));
  expect(rows().props.contentOffset).toEqual({ x: 0, y: 0 });
});

test('W6-P15: View imported plants asks about a Plants form first, then shows the grid without its search', async () => {
  await setup();
  await press(tab('Plants'));
  await fireEvent.changeText(screen.getByTestId('plants-search'), 'fern');
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await press(tab('More'));
  await press(within(screen.getByTestId('more-root')).getByText('Import from Planta'));
  await press(screen.getByText('View imported plants'));
  expect(await screen.findByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Keep editing'));
  expect(selected('More')).toBe(true);
  expect(screen.getByText('View imported plants')).toBeTruthy();
  expect(screen.getByTestId('draft-field', { includeHiddenElements: true }).props.value).toBe('Monty');

  await press(screen.getByText('View imported plants'));
  await press(await screen.findByText('Discard'));
  expect(selected('Plants')).toBe(true);
  expect(screen.queryByTestId('draft-field')).toBeNull();
  expect(screen.getByTestId('plants-search').props.value).toBe('');
  expect(screen.getByText('Synthetic thyme')).toBeTruthy();
  await press(tab('More'));
  expect(screen.getByTestId('more-root')).toBeTruthy();
  expect(screen.queryByText('View imported plants')).toBeNull();
});

type Saved = { routes: { name: string; state?: Saved; params?: object }[] };
const withRootParams = (state: NavigationState, tab: string, params: object) => {
  const copy = JSON.parse(JSON.stringify(state)) as Saved;
  copy.routes.find((route) => route.name === tab)!.state!.routes[0].params = params;
  return copy as unknown as NavigationState;
};

test('W6-P6, P15: a reselect after a restore is seen although the restored page carries an older signal', async () => {
  const h = await setup();
  await press(tab('Plants'));
  const saved = withRootParams(h.states.at(-1)!, 'Plants', { top: 1, clear: 1 });
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  const rows = () => within(screen.getByTestId('plants-root')).getByTestId('plant-rows');
  await fireEvent(rows(), 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 0, y: 200 } } });
  await press(tab('Plants'));
  expect(rows().props.contentOffset).toEqual({ x: 0, y: 0 });
  await restored.unmount();
});

test('W6-P4: an open question and what it would close survive Android recreating the activity', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await systemBack();
  expect(screen.getByText('Discard changes?')).toBeTruthy();
  let saved = h.states.at(-1)!;
  await h.view.unmount();
  let restored = await render(h.tree(saved));
  expect(screen.getByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Keep editing'));
  expect(screen.getByTestId('draft-field').props.value).toBe('Monty');

  await press(tab('More'));
  await press(screen.getByText('Log out'));
  await press(screen.getByTestId('logout-confirm'));
  expect(await screen.findByText('Discard changes?')).toBeTruthy();
  saved = h.states.at(-1)!;
  await restored.unmount();
  restored = await render(h.tree(saved));
  expect(screen.getByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Discard'));
  expect(h.logout).toHaveBeenCalledTimes(1);
  await restored.unmount();
});

test('W6-P21: View imported plants waits for a save, and a later tab tap replaces it', async () => {
  const h = await setup();
  await press(tab('More'));
  await press(within(screen.getByTestId('more-root')).getByText('Import from Planta'));
  await act(async () => h.shell().setSaving('planta', true));
  await press(screen.getByText('View imported plants'));
  expect(selected('More')).toBe(true);
  expect(screen.getByText('View imported plants')).toBeTruthy();
  await act(async () => h.shell().setSaving('planta', false));
  expect(selected('Plants')).toBe(true);

  await press(tab('More'));
  await press(within(screen.getByTestId('more-root')).getByText('Import from Planta'));
  await act(async () => h.shell().setSaving('planta', true));
  await press(screen.getByText('View imported plants'));
  await press(tab('Today'));
  await act(async () => h.shell().setSaving('planta', false));
  expect(selected('Today')).toBe(true);
  await press(tab('More'));
  expect(screen.getByText('View imported plants')).toBeTruthy();
});

test('W6-P22: a list shown again reads again', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await press(tab('More'));
  h.addPlant('p3', 'Synthetic ivy');
  await press(tab('Plants'));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(within(screen.getByTestId('plants-root')).getByText('Synthetic ivy')).toBeTruthy();
});
