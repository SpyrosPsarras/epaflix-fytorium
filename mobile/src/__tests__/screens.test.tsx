import { fireEvent, render, screen } from '@testing-library/react-native';
import { PaperProvider } from 'react-native-paper';
import { LoginPage } from '../screens/LoginPage';
import { UpdateStop } from '../screens/UpdateStop';
import { AboutPage } from '../screens/AboutPage';

const wrap = (node: React.ReactElement) => <PaperProvider>{node}</PaperProvider>;

type Node = { props: { style?: unknown }; children: (Node | string)[] };
// The tallest minHeight anywhere inside a button, which is what bounds its touch target.
function minHeight(node: Node): number {
  const own = ([node.props.style].flat(Infinity) as ({ minHeight?: number } | null | false)[])
    .reduce((max, part) => Math.max(max, (part && part.minHeight) || 0), 0);
  return Math.max(own, ...node.children.filter((child): child is Node => typeof child === 'object').map(minHeight));
}

test('login offline stays a reachable button and explains a tap', async () => {
  const onLogin = jest.fn();
  await render(wrap(<LoginPage busy={false} message={null} online={false} onLogin={onLogin} />));
  expect(screen.getByText("You're logged out. Logging in needs a connection.")).toBeTruthy();
  await fireEvent.press(screen.getByRole('button', { name: 'Log in' }));
  expect(onLogin).not.toHaveBeenCalled();
  expect(screen.getByText('Logging in needs a connection. Turn on Wi-Fi or mobile data, then tap Log in.')).toBeTruthy();
});

test('login shows the server reason and hides it while busy', async () => {
  const view = await render(wrap(<LoginPage busy={false} message="This account isn't in a household yet." online onLogin={jest.fn()} />));
  expect(screen.getByText("This account isn't in a household yet.")).toBeTruthy();
  await view.rerender(wrap(<LoginPage busy message="This account isn't in a household yet." online onLogin={jest.fn()} />));
  expect(screen.queryByText("This account isn't in a household yet.")).toBeNull();
  expect(screen.getByRole('button', { name: 'Logging in…' })).toBeDisabled();
});

test('the update stop names both versions and offers Obtainium only when it is installed', async () => {
  const props = { appVersion: '2.0.0', minimum: '3.1.0', message: null, busy: false, online: true, onCheckAgain: jest.fn(), onLogout: jest.fn() };
  const view = await render(wrap(<UpdateStop {...props} onObtainium={null} />));
  expect(screen.getByRole('header', { name: 'Update Fytorium' })).toBeTruthy();
  expect(screen.getByText("This phone has Fytorium 2.0.0. Your household's server needs 3.1.0 or newer.")).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Open Obtainium' })).toBeNull();
  await view.rerender(wrap(<UpdateStop {...props} onObtainium={jest.fn()} />));
  expect(screen.getByRole('button', { name: 'Open Obtainium' })).toBeTruthy();
  await fireEvent.press(screen.getByRole('button', { name: 'Check again' }));
  expect(props.onCheckAgain).toHaveBeenCalled();
});

test('About lists every data source as a button and copies diagnostics', async () => {
  const copy = jest.fn();
  await render(wrap(<AboutPage appVersion="2.0.0" serverVersion={null} onCopyDiagnostics={copy} />));
  expect(screen.getByText('Server version not loaded yet')).toBeTruthy();
  for (const name of ['GBIF', 'Wikidata', 'NorTaxa', 'Open-Meteo', 'Open Plantbook', 'Perenual', 'Pl@ntNet']) {
    expect(screen.getByRole('button', { name: new RegExp('^' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })).toBeTruthy();
  }
  await fireEvent.press(screen.getByRole('button', { name: 'Copy diagnostics' }));
  expect(copy).toHaveBeenCalled();
});

test('every button on the session screens is at least 48 dp tall', async () => {
  const pages = [
    <LoginPage key="login" busy={false} message={null} online onLogin={jest.fn()} />,
    <UpdateStop key="stop" appVersion="2.0.0" minimum="3.1.0" message={null} busy={false} online onObtainium={jest.fn()} onCheckAgain={jest.fn()} onLogout={jest.fn()} />,
    <AboutPage key="about" appVersion="2.0.0" serverVersion="synthetic" onCopyDiagnostics={jest.fn()} />,
  ];
  for (const page of pages) {
    const view = await render(wrap(page));
    const buttons = screen.getAllByRole('button');
    expect(buttons.length).toBeGreaterThan(0);
    for (const button of buttons) expect(minHeight(button as unknown as Node)).toBeGreaterThanOrEqual(48);
    await view.unmount();
  }
});
