import { readFileSync } from 'node:fs';
import type { ExpoConfig } from 'expo/config';

export type Variant = 'debug' | 'release';
type Env = Record<string, string | undefined>;

const DEFAULT_SERVER_URL = 'https://fytorium.epaflix.com';
const LAB_HOSTS = ['app', 'oidc', 'fake-push', 'localhost'];

function firebaseProject(path: string): string {
  const json = JSON.parse(readFileSync(path, 'utf8'));
  const client = json.client?.find((entry: any) => entry.client_info?.android_client_info?.package_name === 'com.epaflix.fytorium');
  const id = json.project_info?.project_id;
  if (!client || typeof id !== 'string' || !id) throw new Error('Invalid Firebase config');
  return id;
}

export function resolve(env: Env) {
  const variant: Variant = env.FYTORIUM_VARIANT === 'release' ? 'release' : 'debug';
  const serverUrl = env.FYTORIUM_SERVER_URL || (variant === 'debug' ? 'http://app:3000' : DEFAULT_SERVER_URL);
  if (variant === 'release') {
    let url: URL;
    try { url = new URL(serverUrl); } catch { throw new Error('Release server URL must use https://'); }
    if (url.protocol !== 'https:' || !url.hostname) throw new Error('Release server URL must use https://');
    if (LAB_HOSTS.includes(url.hostname.replace(/\.$/, ''))) throw new Error('Release server URL must not be a test host');
  }
  let googleServicesFile = './firebase/google-services.synthetic.json';
  if (variant === 'release') {
    if (!env.GOOGLE_SERVICES_FILE) throw new Error('GOOGLE_SERVICES_FILE is required for release');
    googleServicesFile = env.GOOGLE_SERVICES_FILE;
    if (firebaseProject(googleServicesFile).startsWith('synthetic-') && env.FYTORIUM_FIREBASE_TEST_CONFIG !== 'true') {
      throw new Error('Synthetic Firebase config cannot be released');
    }
  }
  return { variant, serverUrl, googleServicesFile };
}

export default (): ExpoConfig => {
  const { variant, serverUrl, googleServicesFile } = resolve(process.env);
  return {
    name: 'Fytorium',
    slug: 'fytorium',
    version: process.env.FYTORIUM_VERSION_NAME || '2.0.0',
    scheme: 'com.epaflix.fytorium',
    orientation: 'default',
    userInterfaceStyle: 'automatic',
    icon: '../static/icon-512.png',
    android: {
      package: 'com.epaflix.fytorium',
      versionCode: Number(process.env.FYTORIUM_VERSION_CODE || 1),
      allowBackup: false,
      predictiveBackGestureEnabled: true,
      googleServicesFile,
      blockedPermissions: ['android.permission.CAMERA', 'android.permission.RECORD_AUDIO',
        'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE',
        'android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO',
        'android.permission.SYSTEM_ALERT_WINDOW', 'android.permission.VIBRATE'],
    },
    ios: {
      bundleIdentifier: 'com.epaflix.fytorium',
      buildNumber: process.env.FYTORIUM_VERSION_CODE || '1',
      googleServicesFile: process.env.GOOGLE_SERVICE_INFO_PLIST || './firebase/GoogleService-Info.synthetic.plist',
      infoPlist: { ITSAppUsesNonExemptEncryption: false },
    },
    extra: { serverUrl, variant },
    plugins: [
      ['expo-build-properties', {
        android: { minSdkVersion: 29, targetSdkVersion: 35 },
        ios: { useFrameworks: 'dynamic', deploymentTarget: '16.4' },
      }],
      '@react-native-firebase/app',
      '@react-native-firebase/messaging',
      './modules/fytorium-native/plugin.js',
    ],
  };
};
