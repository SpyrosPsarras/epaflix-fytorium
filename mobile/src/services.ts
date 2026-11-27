import { Platform } from 'react-native';
import Constants from 'expo-constants';
import * as Application from 'expo-application';
import * as Device from 'expo-device';
import { getLocales } from 'expo-localization';
import Native from '../modules/fytorium-native';
import { createApi } from './api/client';
import { Credentials } from './session/credentials';
import { createSession } from './session/controller';
import { createPushRegistration } from './push/registration';
import { pushTokens } from './push/tokens';
import * as diagnostics from './diagnostics';

export const serverUrl: string = Constants.expoConfig?.extra?.serverUrl;
export const appVersion = Application.nativeApplicationVersion ?? Constants.expoConfig?.version ?? '0.0.0';
// First-run cleanup runs once per process; login and push registration wait for it to succeed.
const firstRunDone = Native.firstRun();

export const credentials = new Credentials();
let sessionEnded: () => Promise<void> = async () => undefined;

export const api = createApi({
  baseUrl: serverUrl, credentials, transport: Native.request, appVersion,
  platform: Platform.OS === 'ios' ? 'ios' : 'android',
  language: () => getLocales()[0]?.languageTag ?? 'en',
  deviceName: () => (Device.deviceName ?? Device.modelName ?? 'Fytorium phone').trim() || 'Fytorium phone',
  onSessionLost: () => sessionEnded(),
});

export const push = createPushRegistration({
  api, tokens: pushTokens, ios: Platform.OS === 'ios',
  // Native module functions live on the module object, so they are wrapped rather than spread.
  flags: { getFlag: (name) => Native.getFlag(name), setFlag: (name, value) => Native.setFlag(name, value) },
  ready: () => firstRunDone,
  session: () => (credentials.tokens() ? credentials.session : null),
});

export const session = createSession({
  api, appVersion, loggedIn: () => credentials.tokens() !== null, ready: () => firstRunDone, onLoggedIn: () => { void push.sync(); },
});

sessionEnded = async () => {
  await push.sessionEnded();
  Native.cancelRefresh();
  diagnostics.clear();
  diagnostics.record('SESSION_ENDED');
  session.sessionChanged();
};
