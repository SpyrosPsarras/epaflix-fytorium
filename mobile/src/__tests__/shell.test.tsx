import { BackHandler } from 'react-native';
import { act, fireEvent, render, screen, within } from '@testing-library/react-native';
import { back, press, selected, setup, systemBack, tab } from './helpers/shell';
import { useSaving } from '../shell/guards';

jest.mock('expo-image', () => {
  const { Text } = jest.requireActual('react-native');
  const { createElement } = jest.requireActual('react');
  return { Image: ({ source }: { source: { uri: string } }) => createElement(Text, { testID: 'cover' }, source.uri) };
});

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
  expect(screen.getByTestId('more-root')).toBeTruthy();

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

test('W6-P16: a question left open in a hidden tab does not cover Today, and is there again in its tab', async () => {
  const h = await setup();
  await press(tab('More'));
  await press(screen.getByText('Log out'));
  expect(screen.getByTestId('logout-confirm')).toBeTruthy();
  await h.openHome();
  expect(selected('Today')).toBe(true);
  expect(screen.queryByTestId('logout-confirm')).toBeNull();
  await press(within(screen.getByTestId('today-root')).getByText('Synthetic fern'));
  expect(screen.getByTestId('plant-p1')).toBeTruthy();
  await press(tab('More'));
  expect(screen.getByTestId('logout-confirm')).toBeTruthy();
});

test('W6-P16: a notification tap over a form with changes on Today asks first', async () => {
  const h = await setup();
  await press(within(screen.getByTestId('today-root')).getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await h.openHome();
  expect(await screen.findByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Keep editing'));
  expect(screen.getByTestId('draft-field').props.value).toBe('Monty');
  await h.openHome();
  await press(await screen.findByText('Discard'));
  expect(screen.getByTestId('today-root')).toBeTruthy();
  expect(screen.queryByLabelText('Back')).toBeNull();
});

test('W6-P22: a plant page shown again reads again, and Plants and Find read on entry', async () => {
  const h = await setup();
  await press(within(screen.getByTestId('today-root')).getByText('Synthetic fern'));
  await press(tab('More'));
  h.renamePlant('p1', 'Synthetic fernando');
  await press(tab('Today'));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(within(screen.getByTestId('plant-p1')).getByText('Synthetic fernando')).toBeTruthy();

  h.addPlant('p3', 'Synthetic ivy');
  await press(tab('Plants'));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(within(screen.getByTestId('plants-root')).getByText('Synthetic ivy')).toBeTruthy();
  await systemBack();
  await systemBack();
  h.addPlant('p4', 'Synthetic moss');
  await press(within(screen.getByTestId('today-root')).getByText('Find a plant'));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(screen.getAllByText('Synthetic moss').length).toBeGreaterThan(0);
});

test('W6-P6, P4: a reselect question left open when Android recreated the activity still closes the page', async () => {
  const h = await setup();
  await press(tab('Plants'));
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await press(tab('Plants'));
  expect(screen.getByText('Discard changes?')).toBeTruthy();
  const saved = h.states.at(-1)!;
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  await press(screen.getByText('Discard'));
  expect(screen.getByTestId('plants-root')).toBeTruthy();
  expect(screen.queryByTestId('draft-field')).toBeNull();
  await restored.unmount();
});

test('W6-P11: Android keeps Back at the clean Today root, and the app takes it everywhere else', async () => {
  const h = await setup();
  expect(h.handlesBack()).toBe(false);
  await press(within(screen.getByTestId('today-root')).getByText('Find a plant'));
  expect(h.handlesBack()).toBe(true);
  await systemBack();
  expect(h.handlesBack()).toBe(false);
  await press(tab('Plants'));
  expect(h.handlesBack()).toBe(true);
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await press(tab('Today'));
  expect(h.handlesBack()).toBe(true);
  await h.view.unmount();
  expect(h.handlesBack()).toBe(false);
});

test('W6-P22: an open plant reads again when the app comes back, and a cover removed elsewhere goes', async () => {
  const h = await setup();
  h.renamePlant('p1', 'Synthetic fern', 'c1');
  await press(within(screen.getByTestId('today-root')).getByText('Synthetic fern'));
  expect(await screen.findByText('file:///photos/c1.jpg')).toBeTruthy();
  h.renamePlant('p1', 'Synthetic fernando', null);
  await act(async () => { h.care.returned(); await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(within(screen.getByTestId('plant-p1')).getByText('Synthetic fernando')).toBeTruthy();
  expect(screen.queryByTestId('cover')).toBeNull();
});

test('W6-P22: Plants reads the plant list alone, so a task outage does not hide a new plant', async () => {
  const h = await setup();
  h.fail('/api/tasks', true);
  h.addPlant('p3', 'Synthetic ivy');
  await press(tab('Plants'));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(within(screen.getByTestId('plants-root')).getByText('Synthetic ivy')).toBeTruthy();
});

test('W6-P13: Find shows the status line, and a restored Find does not focus its field again', async () => {
  const h = await setup();
  await press(within(screen.getByTestId('today-root')).getByText('Find a plant'));
  const find = () => screen.getByTestId('find-search');
  expect(find().props.autoFocus).toBe(true);
  await fireEvent(find(), 'focus');
  h.fail('/api/plants', true);
  await act(async () => { await h.care.refreshPlants(); });
  expect(screen.getAllByText("Fytorium isn't responding").length).toBeGreaterThan(0);
  await act(async () => { h.care.connectivityChanged(false); });
  expect(screen.getAllByText(/^Offline/).length).toBeGreaterThan(0);
  const saved = h.states.at(-1)!;
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  expect(find().props.autoFocus).toBe(false);
  await restored.unmount();
});

test('W6-P16, P21: a notification tap held for a save still opens Today after Android recreated the activity', async () => {
  const h = await setup();
  await press(tab('More'));
  await act(async () => h.shell().setSaving('more', true));
  await h.openHome();
  expect(selected('More')).toBe(true);
  const saved = h.states.at(-1)!;
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  expect(selected('Today')).toBe(true);
  expect(h.db.all("SELECT key FROM drafts WHERE key LIKE '%/shell/home'")).toEqual([]);
  await restored.unmount();
});

test('W6-P22: Today restored behind another tab reads when it is first shown', async () => {
  const h = await setup();
  await press(tab('More'));
  const saved = h.states.at(-1)!;
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  h.addPlant('p3', 'Synthetic ivy');
  await press(tab('Today'));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(within(screen.getByTestId('today-root')).getByText('Synthetic ivy')).toBeTruthy();
  await restored.unmount();
});

test('W6-P21: a held View imported plants still runs when its page is only covered, and not after it closed', async () => {
  const h = await setup();
  await press(tab('More'));
  await press(within(screen.getByTestId('more-root')).getByText('Import from Planta'));
  await act(async () => h.shell().setSaving('planta', true));
  await press(screen.getByText('View imported plants'));
  await press(screen.getByText('Open imported plant'));
  await act(async () => h.shell().setSaving('planta', false));
  expect(selected('Plants')).toBe(true);
  await press(tab('More'));
  expect(screen.getByTestId('more-root')).toBeTruthy();

  await press(within(screen.getByTestId('more-root')).getByText('Import from Planta'));
  await act(async () => h.shell().setSaving('planta', true));
  await press(screen.getByText('View imported plants'));
  await systemBack();
  await act(async () => h.shell().setSaving('planta', false));
  expect(selected('More')).toBe(true);
  expect(screen.getByTestId('more-root')).toBeTruthy();
});

function HoldSave() {
  useSaving('held-save', true);
  return null;
}

test('W6-P16, P21: a held notification tap opens Today after recreation even when the save ends as the old screen goes', async () => {
  const h = await setup();
  await press(tab('More'));
  await h.view.rerender(h.tree(undefined, <HoldSave />));
  await h.openHome();
  expect(selected('More')).toBe(true);
  const saved = h.states.at(-1)!;
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  expect(selected('Today')).toBe(true);
  expect(h.db.all("SELECT key FROM drafts WHERE key LIKE '%/shell/home'")).toEqual([]);
  await restored.unmount();
});

test('W6-P21: a notification tap replaced by a tab tap during a save is not replayed after recreation', async () => {
  const h = await setup();
  await press(tab('More'));
  await act(async () => h.shell().setSaving('more', true));
  await h.openHome();
  await press(tab('Plants'));
  const saved = h.states.at(-1)!;
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  expect(selected('More')).toBe(true);
  await restored.unmount();
});

// Plants: a search and an Edit draft; More: Planta with a held View imported plants, then an Edit draft above it.
async function twoFormsAndHeldImport() {
  const h = await setup();
  await press(tab('Plants'));
  await fireEvent.changeText(screen.getByTestId('plants-search'), 'fern');
  await press(screen.getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Plants draft');
  await press(tab('More'));
  await press(within(screen.getByTestId('more-root')).getByText('Import from Planta'));
  await act(async () => h.shell().setSaving('planta', true));
  await press(screen.getByText('View imported plants'));
  await press(screen.getByText('Open imported plant'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getAllByTestId('draft-field').at(-1)!, 'More draft');
  await act(async () => h.shell().setSaving('planta', false));
  const drafts = () => screen.getAllByTestId('draft-field', { includeHiddenElements: true }).map((field) => field.props.value).sort();
  return { h, drafts };
}

test('W6-P15, P18: a held View imported plants asks about each form in turn, and Keep editing on any keeps them all', async () => {
  const { drafts } = await twoFormsAndHeldImport();
  expect(await screen.findByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Discard'));
  expect(await screen.findByText('Discard changes?')).toBeTruthy();
  await press(screen.getByText('Keep editing'));
  expect(screen.queryByText('Discard changes?')).toBeNull();
  expect(selected('More')).toBe(true);
  expect(drafts()).toEqual(['More draft', 'Plants draft']);
  expect(screen.getByTestId('plants-search', { includeHiddenElements: true }).props.value).toBe('fern');
});

test('W6-P15, P18: answering Discard for every form closes them, shows the grid without its search and More at its root', async () => {
  const { h } = await twoFormsAndHeldImport();
  await press(await screen.findByText('Discard'));
  await press(await screen.findByText('Discard'));
  expect(selected('Plants')).toBe(true);
  expect(screen.getByTestId('plants-root')).toBeTruthy();
  expect(screen.getByTestId('plants-search').props.value).toBe('');
  expect(screen.queryByTestId('draft-field', { includeHiddenElements: true })).toBeNull();
  expect(h.db.all<{ json: string }>('SELECT json FROM drafts').map((row) => row.json)).not.toContain('"More draft"');
  await press(tab('More'));
  expect(screen.getByTestId('more-root')).toBeTruthy();
});

test('W6-P16, P21: a held notification tap answered with Keep editing is not asked again after recreation', async () => {
  const h = await setup();
  await press(within(screen.getByTestId('today-root')).getByText('Synthetic fern'));
  await press(screen.getByText('Edit'));
  await fireEvent.changeText(screen.getByTestId('draft-field'), 'Monty');
  await act(async () => h.shell().setSaving('edit', true));
  await h.openHome();
  await act(async () => h.shell().setSaving('edit', false));
  await press(await screen.findByText('Keep editing'));
  await press(tab('Plants'));
  const saved = h.states.at(-1)!;
  await h.view.unmount();
  const restored = await render(h.tree(saved));
  expect(screen.queryByText('Discard changes?')).toBeNull();
  expect(selected('Plants')).toBe(true);
  expect(h.db.all("SELECT key FROM drafts WHERE key LIKE '%/shell/home'")).toEqual([]);
  await restored.unmount();
});

test('W6-P22: a shown plant list reads alone when the app comes back, also while the task lists fail', async () => {
  const h = await setup();
  await press(tab('Plants'));
  h.fail('/api/tasks', true);
  h.addPlant('p3', 'Synthetic ivy');
  await act(async () => { h.care.returned(); await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(within(screen.getByTestId('plants-root')).getByText('Synthetic ivy')).toBeTruthy();
});
