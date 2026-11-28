import { requireNativeModule, type EventSubscription } from 'expo-modules-core';

export type Flag = 'push_ready' | 'push_reset_pending' | 'notifications_asked' | 'first_run_done' | 'session_wipe_pending';

export type RequestOptions = {
  method: string;
  url: string;
  headers: Record<string, string>;
  body?: string;
  tokenEndpoint?: boolean;
  binary?: boolean;
};

export type RawResponse = { status: number; headers: Record<string, string>; body: string };

type NativeModule = {
  readonly isDebugBuild: boolean;
  firstRun(): boolean;
  consumeRestoredFromSavedState(): boolean;
  moveTaskToBack(): boolean;
  // Android: whether Back goes to the app (W6-P11); off at Today's root with nothing to ask.
  setBackHandled(value: boolean): void;
  excludeFromBackup(path: string): boolean;
  getFlag(name: Flag): boolean;
  setFlag(name: Flag, value: boolean): void;
  setFlags(values: Partial<Record<Flag, boolean>>): boolean;
  obtainiumPackage(): string | null;
  openObtainium(): boolean;
  consumeOpenHome(): boolean;
  takeRedirects(): string[];
  openLogin(url: string): boolean;
  debugLaunchArgument(name: string): string | null;
  debugLog(message: string): void;
  schedulePeriodicRefresh(): void;
  cancelRefresh(): void;
  request(options: RequestOptions): Promise<RawResponse>;
  addListener(event: 'liveRefresh' | 'openHome' | 'redirect', listener: () => void): EventSubscription;
};

export default requireNativeModule<NativeModule>('FytoriumNative');
