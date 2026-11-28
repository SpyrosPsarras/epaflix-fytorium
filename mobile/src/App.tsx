import { useEffect, useState, useSyncExternalStore } from 'react';
import { AppState, Linking, Platform, ScrollView, StyleSheet, useColorScheme, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import * as Clipboard from 'expo-clipboard';
import * as WebBrowser from 'expo-web-browser';
import NetInfo from '@react-native-community/netinfo';
import { DarkTheme as NavigationDark, DefaultTheme as NavigationLight, type Theme } from '@react-navigation/native';
import { MD3DarkTheme, MD3LightTheme, PaperProvider, Text } from 'react-native-paper';
import Native from '../modules/fytorium-native';
import { palette } from './theme';
import { REDIRECT_URI } from './session/pkce';
import { appVersion, care, connection, drafts, navigationStore, photos, push, session } from './services';
import { refreshInBackground } from './refresh';
import { isOnline } from './online';
import { decideRestore } from './shell/restore';
import { memberKey, type Member } from './data/cache';
import { DepsProvider } from './shell/context';
import { ShellProvider } from './shell/guards';
import { Shell } from './shell/Shell';
import { Stoppable } from './shell/Stoppable';
import { LoginPage } from './screens/LoginPage';
import { UpdateStop } from './screens/UpdateStop';

// Mounted once per logged-in screen: the N3 decision is made here, after the activity exists, never at bundle load.
function ShellHost({ member, theme }: { member: Member | null; theme: Theme }) {
  const [initialState] = useState(() => {
    const decision = decideRestore({
      restored: Native.consumeRestoredFromSavedState(), member: member ? memberKey(member) : null, saved: navigationStore.read(),
    });
    if (decision.wipe) { navigationStore.clear(); drafts.wipe(); }
    return decision.state;
  });
  return (
    <Shell member={member} theme={theme} initialState={initialState}
      onState={(navigationState) => { if (member && navigationState) navigationStore.write(memberKey(member), navigationState); }}
      subscribeOpenHome={subscribeOpenHome} />
  );
}

// The spec's connectivity source is the platform's own reachability; NetInfo must not probe a URL of its own.
NetInfo.configure({ useNativeReachability: true, reachabilityShouldRun: () => false });

function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => NetInfo.addEventListener((state) => {
    const next = isOnline(state, Platform.OS);
    connection.set(next);
    setOnline(next);
    care.connectivityChanged(next);
    if (next) { void push.sync(); void refreshInBackground(); care.returned(); }
  }), []);
  return online;
}

const clock = () => new Date().toTimeString().slice(0, 8);
const setBackHandled = (value: boolean) => Native.setBackHandled(value);
const subscribeOpenHome = (listener: () => void) => {
  if (Native.consumeOpenHome()) setTimeout(listener, 0);
  const subscription = Native.addListener('openHome', listener);
  return () => subscription.remove();
};

export default function App() {
  const scheme = useColorScheme();
  const colors = palette(scheme);
  const theme = { ...(scheme === 'dark' ? MD3DarkTheme : MD3LightTheme), colors: { ...(scheme === 'dark' ? MD3DarkTheme : MD3LightTheme).colors, ...colors } };
  const state = useSyncExternalStore(session.subscribe, session.get);
  const careState = useSyncExternalStore(care.subscribe, care.get);
  const online = useOnline();

  useEffect(() => {
    session.start();
    void push.sync();
    void refreshInBackground();
    Linking.getInitialURL().then((url) => url && session.handleRedirect(url));
    const links = Linking.addEventListener('url', ({ url }) => session.handleRedirect(url));
    // Android keeps redirects that arrived before React was ready, such as after process death during login.
    const takeRedirects = () => Native.takeRedirects().forEach((url) => session.handleRedirect(url));
    takeRedirects();
    const native = Native.addListener('redirect', takeRedirects);
    const foreground = AppState.addEventListener('change', (next) => {
      if (next === 'active') { void push.sync(); void refreshInBackground(); care.returned(); }
    });
    return () => { links.remove(); native.remove(); foreground.remove(); };
  }, []);

  async function login() {
    const url = await session.loginUrl();
    if (!url) return;
    if (Platform.OS === 'android') {
      if (!Native.openLogin(url)) session.loginLaunchFailed();
      return;
    }
    try {
      const result = await WebBrowser.openAuthSessionAsync(url, REDIRECT_URI);
      if (result.type === 'success') session.handleRedirect(result.url);
    } catch {
      session.loginLaunchFailed();
    }
  }

  const copyDiagnostics = () => { void Clipboard.setStringAsync(session.copyDiagnostics(appVersion)); };
  const obtainium = Native.obtainiumPackage() ? () => { Native.openObtainium(); } : null;

  const deps = {
    care, photos, drafts, appVersion, serverVersion: state.serverVersion, online, busy: state.busy,
    logout: () => session.logout(), copyDiagnostics, moveTaskToBack: () => { Native.moveTaskToBack(); },
    setBackHandled,
  };
  const navigationTheme = { ...(scheme === 'dark' ? NavigationDark : NavigationLight), colors: {
    ...(scheme === 'dark' ? NavigationDark : NavigationLight).colors, primary: colors.primary, background: colors.background, card: colors.surface,
    text: colors.onSurface, border: colors.outlineVariant,
  } };

  const page = (content: React.ReactNode) => (
    <ScrollView testID="fytorium-root" style={{ backgroundColor: colors.background }} contentContainerStyle={styles.page}>
      <Text accessibilityRole="header" variant="headlineMedium" style={{ color: colors.onBackground }}>Fytorium</Text>
      {content}
    </ScrollView>
  );
  const stop = state.updateRequired ? page(
    <UpdateStop appVersion={appVersion} minimum={state.minAppVersion} message={state.message} busy={state.busy} online={online}
      onObtainium={obtainium} onCheckAgain={() => session.checkAgain(clock)} onLogout={() => session.logout()} />,
  ) : null;

  if (state.loggedIn) {
    // The update stop covers the shell instead of replacing it, so its stacks and drafts are there when it clears.
    return (
      <PaperProvider theme={theme}>
        <DepsProvider value={deps}>
          <ShellProvider>
            <Stoppable stop={stop}>
              <View testID={stop ? undefined : 'fytorium-root'} style={{ flex: 1 }}>
                <ShellHost member={careState.member} theme={navigationTheme} />
              </View>
            </Stoppable>
          </ShellProvider>
        </DepsProvider>
        <StatusBar style="auto" />
      </PaperProvider>
    );
  }

  return (
    <PaperProvider theme={theme}>
      {stop ?? page(<LoginPage key={String(online)} busy={state.busy} message={state.message} online={online} onLogin={() => { void login(); }} />)}
      <StatusBar style="auto" />
    </PaperProvider>
  );
}

const styles = StyleSheet.create({ page: { flexGrow: 1, padding: 24, paddingTop: 64, gap: 24 } });
