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
// First-run cleanup runs once per process; credentials, login and push registration wait for it to succeed.
const firstRunDone = Native.firstRun();

// Native module functions live on the module object, so they are wrapped rather than spread.
const flags = {
  getFlag: (name: Parameters<typeof Native.getFlag>[0]) => Native.getFlag(name),
  setFlag: (name: Parameters<typeof Native.setFlag>[0], value: boolean) => Native.setFlag(name, value),
  // A failed commit is retried once; SharedPreferences keeps the values in memory either way.
  setFlags: (values: Parameters<typeof Native.setFlags>[0]) => { if (!Native.setFlags(values)) Native.setFlags(values); },
};

export const credentials = new Credentials(firstRunDone, {
  get: () => Native.getFlag('session_wipe_pending'),
  set: (value) => Native.setFlag('session_wipe_pending', value),
});

const late = { sessionEnded: async () => undefined as void, updateRequired: (_minimum: string | null) => undefined as void };

export const api = createApi({
  baseUrl: serverUrl, credentials, transport: Native.request, appVersion,
  platform: Platform.OS === 'ios' ? 'ios' : 'android',
  language: () => getLocales()[0]?.languageTag ?? 'en',
  deviceName: () => (Device.deviceName ?? Device.modelName ?? 'Fytorium phone').trim() || 'Fytorium phone',
  beforeWipe: () => push.closeGate(),
  onSessionLost: () => late.sessionEnded(),
  onUpdateRequired: (minimum) => late.updateRequired(minimum),
});

export const push = createPushRegistration({
  api, tokens: pushTokens, flags, ios: Platform.OS === 'ios',
  ready: () => firstRunDone,
  session: () => (credentials.tokens() ? credentials.session : null),
});

export const session = createSession({
  api, appVersion, loggedIn: () => credentials.tokens() !== null, ready: () => firstRunDone, onLoggedIn: () => { void push.sync(); },
});

late.updateRequired = session.updateRequired;
late.sessionEnded = async () => {
  await push.sessionEnded();
  Native.cancelRefresh();
  diagnostics.clear();
  diagnostics.record('SESSION_ENDED');
  session.sessionChanged();
};
