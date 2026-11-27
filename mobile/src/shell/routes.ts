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
export type StackParams = PageParams & { TodayRoot: undefined; PlantsRoot: undefined; JournalRoot: undefined; MoreRoot: undefined };

export function tabsFor(role: Role | null): TabName[] {
  return role === 'sitter' ? ['Today', 'Plants', 'More'] : ['Today', 'Plants', 'Journal', 'More'];
}

// W6-P17 with its build amendment: which pages a role may keep open.
export function allowed(route: string, role: Role | null): boolean {
  if (role === 'sitter') return ['TodayRoot', 'PlantsRoot', 'MoreRoot', 'Notifications', 'About'].includes(route);
  if (role === 'family') return !['Planta', 'Members'].includes(route);
  return true;
}

type AnyState = NavigationState | PartialState<NavigationState>;

// Drops tabs and pages the role may not see; a stack whose top went shows the page under it.
export function filterState(state: AnyState, role: Role | null): AnyState {
  const tabs = tabsFor(role);
  const routes = state.routes.filter((tab) => tabs.includes(tab.name as TabName)).map((tab) => {
    const stack = tab.state;
    if (!stack) return tab;
    const pages = stack.routes.filter((page) => allowed(page.name, role));
    return { ...tab, state: { ...stack, routes: pages, index: Math.max(0, Math.min(stack.index ?? pages.length - 1, pages.length - 1)) } as never };
  });
  const selected = state.routes[state.index ?? 0]?.name;
  const index = Math.max(0, routes.findIndex((tab) => tab.name === selected));
  return { ...state, routes, index, stale: true } as never;
}

// A key that names a page in its tab and position, for drafts that must go when the page leaves its stack.
export const pageKey = (memberKey: string, tab: string, routeKey: string) => `${memberKey}/${tab}/${routeKey}`;
