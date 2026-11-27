import { act, fireEvent, render, screen } from '@testing-library/react-native';
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
const plantsJson = JSON.stringify([
  { id: 'p1', display_name: 'Synthetic fern', cover_photo_id: null },
  { id: 'p2', display_name: 'Synthetic thyme', cover_photo_id: null },
]);

async function setup(member: Member = owner) {
  const db = nodeDb();
  const cache = new CareCache(db);
  cache.bind(JSON.stringify({ member: { ...member, display_name: 'Synthetic owner' } }));
  cache.save(member, { plants: plantsJson }, 1);
  const api = { request: async (path: string): Promise<RawResponse> => ({ status: path.startsWith('/api/plants/') ? 200 : 404, headers: {}, body: path === '/api/plants/p1' ? JSON.stringify({ id: 'p1', display_name: 'Synthetic fern' }) : '{}' }) };
  const care = createRepository({ api: api as never, cache, online: () => false, onMemberChanged: () => undefined });
  care.restore();
  const drafts = new Drafts(db);
  const photos = new PhotoCache(db, { uri: () => null, write: async () => undefined, remove: () => undefined, removeAll: () => undefined }, api as never, () => false);
  const states: (NavigationState | undefined)[] = [];
  const homes = new Set<() => void>();
  let shell: ReturnType<typeof useShell> | null = null;
  function Capture() { shell = useShell(); return null; }
  const deps = { care, photos, drafts, appVersion: '2.0.0', serverVersion: null, online: true, busy: false, logout: jest.fn(), copyDiagnostics: jest.fn() };
  const tree = (current: Member, initialState?: NavigationState) => (
    <PaperProvider>
      <DepsProvider value={deps}>
        <ShellProvider>
          <Capture />
          <Shell member={current} theme={DefaultTheme} initialState={initialState} onState={(state) => states.push(state)}
            onMemberChanged={() => drafts.wipe()} subscribeOpenHome={(listener) => { homes.add(listener); return () => homes.delete(listener); }} />
        </ShellProvider>
      </DepsProvider>
    </PaperProvider>
  );
  const view = await render(tree(member));
  return {
    view, db, drafts, states, tree,
    shell: () => shell!,
    openHome: () => act(() => homes.forEach((listener) => listener())),
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
  await h.view.rerender(h.tree({ id: 'm1', role: 'family' }));
  await press(tab('Today'));
  expect(screen.getByTestId('today-root')).toBeTruthy();
  await press(tab('More'));
  expect(screen.getByTestId('more-root')).toBeTruthy();
  await press(screen.getByText('Notifications'));
  await h.view.rerender(h.tree({ id: 'm1', role: 'sitter' }));
  expect(screen.queryByTestId('tab-Journal')).toBeNull();
  expect(selected('More')).toBe(true);
  expect(screen.getByText('This page arrives in a later update.')).toBeTruthy();
  await h.view.rerender(h.tree({ id: 'm2', role: 'owner' }));
  expect(selected('Today')).toBe(true);
  await press(tab('More'));
  expect(screen.getByTestId('more-root')).toBeTruthy();
});

test('W6-P21: a tab tap during a save waits for it to end', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await act(async () => h.shell().setSaving('plant-edit', true));
  await press(tab('More'));
  expect(selected('Plants')).toBe(true);
  await act(async () => h.shell().setSaving('plant-edit', false));
  expect(selected('More')).toBe(true);
});

test('W6-P4, N3: saved stacks and drafts come back after Android recreated the activity', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await press(tab('More'));
  await press(screen.getByText('About'));
  const saved = h.states.at(-1)!;
  // Process death runs no unmount cleanup, so the drafts table keeps what it had.
  const kept = h.db.all<{ key: string; json: string }>('SELECT key, json FROM drafts');
  await h.view.unmount();
  for (const row of kept) h.db.run('INSERT OR REPLACE INTO drafts (key, json) VALUES (?, ?)', row.key, row.json);
  const restored = await render(h.tree(owner, saved));
  expect(screen.getByText('Copy diagnostics')).toBeTruthy();
  await press(screen.getByTestId('tab-Plants'));
  expect(screen.getByTestId('draft-field').props.value).toBe('Monty');
  await restored.unmount();
});
