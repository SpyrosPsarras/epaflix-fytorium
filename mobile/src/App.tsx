import { useEffect, useState, useSyncExternalStore } from 'react';
import { AppState, Linking, Platform, ScrollView, StyleSheet, useColorScheme, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import * as Clipboard from 'expo-clipboard';
import * as WebBrowser from 'expo-web-browser';
import NetInfo from '@react-native-community/netinfo';
import { DarkTheme as NavigationDark, DefaultTheme as NavigationLight, type NavigationState } from '@react-navigation/native';
import { MD3DarkTheme, MD3LightTheme, PaperProvider, Text } from 'react-native-paper';
import Native from '../modules/fytorium-native';
import { palette } from './theme';
import { REDIRECT_URI } from './session/pkce';
import { appVersion, care, connection, drafts, navigationStore, photos, push, session } from './services';
import { memberKey } from './data/cache';
import { DepsProvider } from './shell/context';
import { ShellProvider } from './shell/guards';
import { Shell } from './shell/Shell';
import { LoginPage } from './screens/LoginPage';
import { UpdateStop } from './screens/UpdateStop';

function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => NetInfo.addEventListener((state) => {
    const next = state.isConnected === true && state.isInternetReachable !== false;
    connection.set(next);
    setOnline(next);
    care.connectivityChanged(next);
    if (next) { void push.sync(); void care.refresh(); }
  }), []);
  return online;
}

const clock = () => new Date().toTimeString().slice(0, 8);
const subscribeOpenHome = (listener: () => void) => {
  if (Native.consumeOpenHome()) setTimeout(listener, 0);
  const subscription = Native.addListener('openHome', listener);
  return () => subscription.remove();
};

// N3: Android recreated the activity, so pages and drafts come back; any other start opens Today at its root.
function restoredNavigation(): NavigationState | undefined {
  const member = care.get().member;
  const saved = navigationStore.read();
  if (Native.restoredFromSavedState() && member && saved?.member === memberKey(member)) return saved.state as NavigationState;
  navigationStore.clear();
  drafts.wipe();
  return undefined;
}
care.restore();
const initialNavigation = restoredNavigation();

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
    void care.refresh();
    Linking.getInitialURL().then((url) => url && session.handleRedirect(url));
    const links = Linking.addEventListener('url', ({ url }) => session.handleRedirect(url));
    // Android keeps redirects that arrived before React was ready, such as after process death during login.
    const takeRedirects = () => Native.takeRedirects().forEach((url) => session.handleRedirect(url));
    takeRedirects();
    const native = Native.addListener('redirect', takeRedirects);
    const foreground = AppState.addEventListener('change', (next) => {
      if (next === 'active') { void push.sync(); void care.refresh(); }
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
    logout: () => session.logout(), copyDiagnostics,
  };
  const navigationTheme = { ...(scheme === 'dark' ? NavigationDark : NavigationLight), colors: {
    ...(scheme === 'dark' ? NavigationDark : NavigationLight).colors, primary: colors.primary, background: colors.background, card: colors.surface,
    text: colors.onSurface, border: colors.outlineVariant,
  } };

  if (state.loggedIn && !state.updateRequired) {
    return (
      <PaperProvider theme={theme}>
        <DepsProvider value={deps}>
          <ShellProvider>
            <View testID="fytorium-root" style={{ flex: 1 }}>
            <Shell
              member={careState.member} theme={navigationTheme} initialState={initialNavigation}
              onState={(navigationState) => { if (careState.member && navigationState) navigationStore.write(memberKey(careState.member), navigationState); }}
              onMemberChanged={() => undefined} subscribeOpenHome={subscribeOpenHome} />
            </View>
          </ShellProvider>
        </DepsProvider>
        <StatusBar style="auto" />
      </PaperProvider>
    );
  }

  return (
    <PaperProvider theme={theme}>
      <ScrollView testID="fytorium-root" style={{ backgroundColor: colors.background }} contentContainerStyle={styles.page}>
        <Text accessibilityRole="header" variant="headlineMedium" style={{ color: colors.onBackground }}>Fytorium</Text>
        {state.updateRequired
          ? <UpdateStop appVersion={appVersion} minimum={state.minAppVersion} message={state.message} busy={state.busy} online={online}
              onObtainium={obtainium} onCheckAgain={() => session.checkAgain(clock)} onLogout={() => session.logout()} />
          : <LoginPage key={String(online)} busy={state.busy} message={state.message} online={online} onLogin={() => { void login(); }} />}
      </ScrollView>
      <StatusBar style="auto" />
    </PaperProvider>
  );
}

const styles = StyleSheet.create({ page: { flexGrow: 1, padding: 24, paddingTop: 64, gap: 24 } });
