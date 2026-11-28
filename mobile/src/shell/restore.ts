import type { NavigationState } from '@react-navigation/native';

export type SavedNavigation = { member: string; state: unknown } | null;

// N3: pages and drafts come back only when Android recreated this activity from saved state, for the same member and role.
// Anything else (a cold start, a new login, a background start that came first) starts at Today and drops the drafts.
export function decideRestore(input: { restored: boolean; member: string | null; saved: SavedNavigation }): { state?: NavigationState; wipe: boolean } {
  if (input.restored && input.member && input.saved?.member === input.member) return { state: input.saved.state as NavigationState, wipe: false };
  return { wipe: true };
}
