import { fireEvent, render, screen, within } from '@testing-library/react-native';
import type { NavigationState } from '@react-navigation/native';
import { press, setup, tab } from './helpers/shell';

jest.mock('expo-image', () => ({ Image: () => null }));

// Its own file, so the shell's signal counter starts as it does in a new process.
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

