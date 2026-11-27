import {
  deleteToken, getMessaging, getToken, onTokenRefresh, registerDeviceForRemoteMessages, unregisterDeviceForRemoteMessages,
} from '@react-native-firebase/messaging';

export type PushTokens = {
  get(): Promise<string>;
  delete(): Promise<void>;
  onRefresh(listener: (token: string) => void): () => void;
  registerDevice(): Promise<void>;
  unregisterDevice(): Promise<void>;
};

function firebase(): PushTokens {
  const messaging = getMessaging();
  return {
    get: () => getToken(messaging),
    delete: () => deleteToken(messaging),
    onRefresh: (listener) => onTokenRefresh(messaging, listener),
    registerDevice: () => registerDeviceForRemoteMessages(messaging),
    unregisterDevice: () => unregisterDeviceForRemoteMessages(messaging),
  };
}

// Debug builds never ask Google for a token; the bundler drops this branch from release builds.
export const pushTokens: PushTokens = process.env.EXPO_PUBLIC_FYTORIUM_VARIANT === 'debug'
  ? require('./syntheticTokens').syntheticTokens()
  : firebase();
