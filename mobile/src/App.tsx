import { useEffect, useState, useSyncExternalStore } from 'react';
import { AppState, Linking, Platform, ScrollView, StyleSheet, useColorScheme, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import * as Clipboard from 'expo-clipboard';
import * as WebBrowser from 'expo-web-browser';
import NetInfo from '@react-native-community/netinfo';
import { Button, MD3DarkTheme, MD3LightTheme, PaperProvider, Text } from 'react-native-paper';
import Native from '../modules/fytorium-native';
import { palette } from './theme';
import { REDIRECT_URI } from './session/pkce';
import { appVersion, push, session } from './services';
import { LoginPage } from './screens/LoginPage';
import { UpdateStop } from './screens/UpdateStop';
import { AboutPage } from './screens/AboutPage';
import { LogoutDialog } from './screens/LogoutDialog';

function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => NetInfo.addEventListener((state) => {
    const next = state.isConnected === true && state.isInternetReachable !== false;
    setOnline(next);
    if (next) void push.sync();
  }), []);
  return online;
}

const clock = () => new Date().toTimeString().slice(0, 8);

export default function App() {
  const scheme = useColorScheme();
  const colors = palette(scheme);
  const theme = { ...(scheme === 'dark' ? MD3DarkTheme : MD3LightTheme), colors: { ...(scheme === 'dark' ? MD3DarkTheme : MD3LightTheme).colors, ...colors } };
  const state = useSyncExternalStore(session.subscribe, session.get);
  const online = useOnline();
  const [page, setPage] = useState<'home' | 'about'>('home');
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    session.start();
    void push.sync();
    Linking.getInitialURL().then((url) => url && session.handleRedirect(url));
    const links = Linking.addEventListener('url', ({ url }) => session.handleRedirect(url));
    // Android keeps redirects that arrived before React was ready, such as after process death during login.
    const takeRedirects = () => Native.takeRedirects().forEach((url) => session.handleRedirect(url));
    takeRedirects();
    const native = Native.addListener('redirect', takeRedirects);
    const foreground = AppState.addEventListener('change', (next) => { if (next === 'active') void push.sync(); });
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

  let body;
  if (state.updateRequired) {
    body = <UpdateStop appVersion={appVersion} minimum={state.minAppVersion} message={state.message} busy={state.busy} online={online}
      onObtainium={obtainium} onCheckAgain={() => session.checkAgain(clock)} onLogout={() => session.logout()} />;
  } else if (!state.loggedIn) {
    body = <LoginPage key={String(online)} busy={state.busy} message={state.message} online={online} onLogin={() => { void login(); }} />;
  } else if (page === 'about' && state.loggedIn) {
    body = (
      <View style={{ gap: 12 }}>
        <Button icon="arrow-left" onPress={() => setPage('home')} style={{ alignSelf: 'flex-start' }} contentStyle={{ minHeight: 48 }}>Back</Button>
        <AboutPage appVersion={appVersion} serverVersion={state.serverVersion} onCopyDiagnostics={copyDiagnostics} />
      </View>
    );
  } else {
    // shortcut: a holding page until the W3 tab shell replaces it.
    body = (
      <View style={{ gap: 12 }} testID="logged-in">
        <Text>You&apos;re logged in.</Text>
        <Button mode="outlined" onPress={() => setPage('about')} contentStyle={{ minHeight: 48 }}>About</Button>
        <Button onPress={() => setConfirming(true)} disabled={state.busy} contentStyle={{ minHeight: 48 }}>Log out</Button>
        <Text style={{ minHeight: 48 }}>{state.busy ? '' : state.message ?? ''}</Text>
      </View>
    );
  }

  return (
    <PaperProvider theme={theme}>
      <ScrollView testID="fytorium-root" style={{ backgroundColor: colors.background }} contentContainerStyle={styles.page}>
        <Text accessibilityRole="header" variant="headlineMedium" style={{ color: colors.onBackground }}>Fytorium</Text>
        {body}
      </ScrollView>
      <LogoutDialog visible={confirming} online={online} onDismiss={() => setConfirming(false)}
        onConfirm={() => { setConfirming(false); setPage('home'); session.logout(); }} />
      <StatusBar style="auto" />
    </PaperProvider>
  );
}

const styles = StyleSheet.create({ page: { flexGrow: 1, padding: 24, paddingTop: 64, gap: 24 } });
