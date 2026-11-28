import Storage from 'expo-sqlite/kv-store';
import Native from '../../modules/fytorium-native';
import type { PushTokens } from './tokens';

const CURRENT = 'synthetic.push.current';
const FAILURES = 'synthetic.push.deleteFailures';
const fresh = () => 'synthetic-fcm-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
// e2e counts these in the app's key-value store; logcat can drop lines under load.
const log = (event: 'failed' | 'deleted') => {
  const key = 'synthetic.push.' + event;
  Storage.setItemSync(key, String(Number(Storage.getItemSync(key) ?? '0') + 1));
  Native.debugLog('FytoriumSyntheticPush ' + event);
};

// e2e seeds the token and the number of failing deletions through launch arguments.
export function syntheticTokens(): PushTokens {
  // Launch arguments come from the activity, which exists only once the app is on screen.
  let seeded = false;
  const seed = () => {
    if (seeded) return;
    seeded = true;
    const token = Native.debugLaunchArgument('pushToken');
    if (token) Storage.setItemSync(CURRENT, token);
    const failures = Native.debugLaunchArgument('pushDeleteFailures');
    if (failures) Storage.setItemSync(FAILURES, failures);
  };
  const listeners = new Set<(token: string) => void>();
  const current = () => {
    seed();
    const token = Storage.getItemSync(CURRENT) ?? fresh();
    Storage.setItemSync(CURRENT, token);
    return token;
  };
  return {
    get: async () => current(),
    delete: async () => {
      seed();
      const left = Number(Storage.getItemSync(FAILURES) ?? '0');
      if (left > 0) {
        Storage.setItemSync(FAILURES, String(left - 1));
        log('failed');
        throw new Error('synthetic delete failure');
      }
      log('deleted');
      Storage.removeItemSync(CURRENT);
    },
    onRefresh: (listener) => { listeners.add(listener); return () => listeners.delete(listener); },
    registerDevice: async () => undefined,
    unregisterDevice: async () => undefined,
  };
}
