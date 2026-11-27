import type { NavigationState, PartialState } from '@react-navigation/native';
import type { Role } from '../data/cache';

export type TabName = 'Today' | 'Plants' | 'Journal' | 'More';
export const TAB_ROOTS: Record<TabName, string> = { Today: 'TodayRoot', Plants: 'PlantsRoot', Journal: 'JournalRoot', More: 'MoreRoot' };

// W6-P2: the pages the shell opens over a tab, with their parameters.
export type PageParams = {
  Plant: { id: string; occurrence?: string };
  PlantEdit: { id: string };
  Find: undefined;
  Add: undefined;
  Sites: undefined;
  Planta: undefined;
  Weather: undefined;
  Notifications: undefined;
  Members: undefined;
  CustomTasks: undefined;
  Unmatched: undefined;
  Species: { key: string };
  About: undefined;
  JournalPick: undefined;
  JournalAdd: { plantId: string };
};
// A root's params are signals from the shell: `top` scrolls it to the top (W6-P6), `clear` drops the Plants search (W6-P15).
export type RootParams = { top?: number; clear?: number } | undefined;
export type StackParams = PageParams & { TodayRoot: RootParams; PlantsRoot: RootParams; JournalRoot: RootParams; MoreRoot: RootParams };

// Until the member is known only the tabs every role has show, so a sitter never sees Journal flash by.
export function tabsFor(role: Role | null): TabName[] {
  return role === 'owner' || role === 'family' ? ['Today', 'Plants', 'Journal', 'More'] : ['Today', 'Plants', 'More'];
}

// W6-P17 with its build amendment: which pages a role may keep open (as Kotlin's AppShell `allowed`).
export function allowed(route: string, role: Role | null): boolean {
  if (role === 'sitter') return ['TodayRoot', 'PlantsRoot', 'MoreRoot', 'Plant', 'Find', 'Notifications', 'About'].includes(route);
  if (role === 'family') return !['Planta', 'Members', 'Sites'].includes(route);
  return true;
}

type AnyState = NavigationState | PartialState<NavigationState>;

// Drops tabs the role may not see, and in each stack the first page it may not open with every page above it;
// the stack then shows the page under it.
export function filterState(state: AnyState, role: Role | null): AnyState {
  const tabs = tabsFor(role);
  const routes = state.routes.filter((tab) => tabs.includes(tab.name as TabName)).map((tab) => {
    const stack = tab.state;
    if (!stack) return tab;
    const blocked = stack.routes.findIndex((page) => !allowed(page.name, role));
    const pages = blocked < 0 ? stack.routes : stack.routes.slice(0, blocked);
    return { ...tab, state: { ...stack, routes: pages, index: Math.max(0, Math.min(stack.index ?? pages.length - 1, pages.length - 1)) } as never };
  });
  const selected = state.routes[state.index ?? 0]?.name;
  const index = Math.max(0, routes.findIndex((tab) => tab.name === selected));
  return { ...state, routes, index, stale: true } as never;
}

// Where a page's saved state lives: under its member and route, so it can go when the route leaves its stack.
export const pageKey = (memberKey: string, routeKey: string, name?: string) => `${memberKey}/${routeKey}${name ? `/${name}` : ''}`;

// Every route key in a navigation state, tabs and their pages.
export function routeKeys(state: AnyState | undefined): Set<string> {
  const keys = new Set<string>();
  const walk = (node: AnyState | undefined) => node?.routes.forEach((route) => {
    if (route.key) keys.add(route.key);
    walk(route.state as AnyState | undefined);
  });
  walk(state);
  return keys;
}
