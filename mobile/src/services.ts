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
import Storage from 'expo-sqlite/kv-store';
import * as diagnostics from './diagnostics';
import { openAppDb } from './data/db';
import { CareCache } from './data/cache';
import { createRepository } from './data/repository';
import { PhotoCache, fileDisk } from './data/photos';
import { Drafts } from './data/drafts';

export const serverUrl: string = Constants.expoConfig?.extra?.serverUrl;
export const appVersion = Application.nativeApplicationVersion ?? Constants.expoConfig?.version ?? '0.0.0';
// First-run cleanup runs once per process; credentials, login and push registration wait for it to succeed.
const firstRunDone = Native.firstRun();

// Native module functions live on the module object, so they are wrapped rather than spread.
const flags = {
  getFlag: (name: Parameters<typeof Native.getFlag>[0]) => Native.getFlag(name),
  setFlag: (name: Parameters<typeof Native.setFlag>[0], value: boolean) => Native.setFlag(name, value),
  // A failed commit is retried once; SharedPreferences keeps the values in memory either way.
  setFlags: (values: Parameters<typeof Native.setFlags>[0]) => Native.setFlags(values) || Native.setFlags(values),
};

export const credentials = new Credentials(firstRunDone, {
  get: () => Native.getFlag('session_wipe_pending'),
  set: (value) => Native.setFlags({ session_wipe_pending: value }),
});

// The 6-hour read: WorkManager through the native worker on Android, BGTaskScheduler through expo-background-task on iOS.
export const BACKGROUND_TASK = 'fytorium-care-refresh';
export const background = {
  start() {
    if (Platform.OS === 'android') { Native.schedulePeriodicRefresh(); return; }
    const BackgroundTask = require('expo-background-task') as typeof import('expo-background-task');
    void BackgroundTask.registerTaskAsync(BACKGROUND_TASK, { minimumInterval: 360 }).catch(() => undefined);
  },
  stop() {
    if (Platform.OS === 'android') { Native.cancelRefresh(); return; }
    const BackgroundTask = require('expo-background-task') as typeof import('expo-background-task');
    void BackgroundTask.unregisterTaskAsync(BACKGROUND_TASK).catch(() => undefined);
  },
};

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

// Validated network as NetInfo reports it; the App keeps it current.
let online = true;
export const connection = { online: () => online, set: (value: boolean) => { online = value; } };

const db = openAppDb();
export const drafts = new Drafts(db);
export const photos = new PhotoCache(db, fileDisk(), api, connection.online);
const NAV = 'nav.state';
// Navigation is brought back only when Android recreated the activity (N3) and for the same member and role.
export const navigationStore = {
  read(): { member: string; state: unknown } | null {
    try { return JSON.parse(Storage.getItemSync(NAV) ?? 'null'); } catch { return null; }
  },
  write(member: string, state: unknown) { Storage.setItemSync(NAV, JSON.stringify({ member, state })); },
  clear() { Storage.removeItemSync(NAV); },
};
export const care = createRepository({
  api, cache: new CareCache(db), online: connection.online,
  // A new member or role starts without the last one's photos, drafts or pages.
  onMemberChanged: () => { photos.wipe(); drafts.wipe(); navigationStore.clear(); },
});

export const session = createSession({
  api, appVersion, loggedIn: () => credentials.tokens() !== null, ready: () => firstRunDone,
  onLoggedIn: () => {
    void push.sync();
    background.start();
    void care.refresh();
  },
});

late.updateRequired = session.updateRequired;
late.sessionEnded = async () => {
  await push.sessionEnded();
  background.stop();
  care.wipe();
  diagnostics.clear();
  diagnostics.record('SESSION_ENDED');
  session.sessionChanged();
};
