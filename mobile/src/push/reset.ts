import type { Flag } from '../../modules/fytorium-native';

export type ResetDeps = {
  getFlag(name: Flag): boolean;
  setFlag(name: Flag, value: boolean): void;
  deleteToken(): Promise<void>;
};

// Registration may run only after the previous session's FCM token is gone (design: Push token lifecycle, step 2).
export async function readyToRegister(deps: ResetDeps): Promise<boolean> {
  if (!deps.getFlag('push_reset_pending')) return true;
  try {
    await deps.deleteToken();
  } catch {
    return false;
  }
  deps.setFlag('push_reset_pending', false);
  return true;
}
