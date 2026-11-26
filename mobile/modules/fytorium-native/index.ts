import { requireNativeModule, type EventSubscription } from 'expo-modules-core';

export type Flag = 'push_ready' | 'push_reset_pending' | 'notifications_asked' | 'first_run_done';

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
  restoredFromSavedState(): boolean;
  getFlag(name: Flag): boolean;
  setFlag(name: Flag, value: boolean): void;
  obtainiumPackage(): string | null;
  consumeOpenHome(): boolean;
  debugLaunchArgument(name: string): string | null;
  schedulePeriodicRefresh(): void;
  cancelRefresh(): void;
  request(options: RequestOptions): Promise<RawResponse>;
  addListener(event: 'liveRefresh' | 'openHome', listener: () => void): EventSubscription;
};

export default requireNativeModule<NativeModule>('FytoriumNative');
