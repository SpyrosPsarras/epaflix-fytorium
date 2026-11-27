import { isOnline } from '../online';

test('a connected iPhone is online although NetInfo reports no reachability without its probe', () => {
  expect(isOnline({ isConnected: true, isInternetReachable: false }, 'ios')).toBe(true);
  expect(isOnline({ isConnected: false, isInternetReachable: false }, 'ios')).toBe(false);
});

test('Android is online only on a network the system validated', () => {
  expect(isOnline({ isConnected: true, isInternetReachable: true }, 'android')).toBe(true);
  expect(isOnline({ isConnected: true, isInternetReachable: null }, 'android')).toBe(true);
  expect(isOnline({ isConnected: true, isInternetReachable: false }, 'android')).toBe(false);
  expect(isOnline({ isConnected: null, isInternetReachable: true }, 'android')).toBe(false);
});
