import { useEffect, useRef, useState } from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import {
  CommonActions, NavigationContainer, StackActions, createNavigationContainerRef, type NavigationState, type Theme,
} from '@react-navigation/native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { Member } from '../data/cache';
import { useShell } from './guards';
import {
  AboutRoute, FindPage, JournalAdd, JournalPick, JournalRoot, MoreRoot, PageHeader, PlantEdit, PlantPage, PlantsRoot, StubPage, TodayRoot,
} from './pages';
import { filterState, tabsFor, TAB_ROOTS, type StackParams, type TabName } from './routes';

export const navigation = createNavigationContainerRef();

const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator<StackParams>();

const ICONS: Record<TabName, keyof typeof MaterialCommunityIcons.glyphMap> = {
  Today: 'calendar-check', Plants: 'sprout', Journal: 'image-multiple', More: 'dots-horizontal',
};

// The pages every tab can open over its root (W6-P2); each tab keeps its own stack of them (W6-P1).
function pages() {
  return (
    <>
      <Stack.Screen name="Plant" component={PlantPage} options={{ title: 'Plant' }} />
      <Stack.Screen name="PlantEdit" component={PlantEdit} options={{ title: 'Edit plant' }} />
      <Stack.Screen name="Find" component={FindPage} options={{ title: 'Find a plant' }} />
      <Stack.Screen name="Add" component={StubPage} options={{ title: 'Add plant' }} />
      <Stack.Screen name="Sites" component={StubPage} options={{ title: 'Sites' }} />
      <Stack.Screen name="Planta" component={StubPage} options={{ title: 'Import from Planta' }} />
      <Stack.Screen name="Weather" component={StubPage} options={{ title: 'Weather and feed' }} />
      <Stack.Screen name="Notifications" component={StubPage} options={{ title: 'Notifications' }} />
      <Stack.Screen name="Members" component={StubPage} options={{ title: 'Members' }} />
      <Stack.Screen name="CustomTasks" component={StubPage} options={{ title: 'Custom tasks' }} />
      <Stack.Screen name="Unmatched" component={StubPage} options={{ title: 'Species without care data' }} />
      <Stack.Screen name="Species" component={StubPage} options={{ title: 'Species care' }} />
      <Stack.Screen name="About" component={AboutRoute} options={{ title: 'About' }} />
      <Stack.Screen name="JournalPick" component={JournalPick} options={{ title: 'Choose a plant' }} />
      <Stack.Screen name="JournalAdd" component={JournalAdd} options={{ title: 'Add a photo' }} />
    </>
  );
}

const ROOTS = { Today: TodayRoot, Plants: PlantsRoot, Journal: JournalRoot, More: MoreRoot };

function tabStack(tab: TabName) {
  return function TabStack() {
    return (
      <Stack.Navigator screenOptions={{ header: (props) => <PageHeader {...props} /> }}>
        <Stack.Screen name={TAB_ROOTS[tab] as keyof StackParams} component={ROOTS[tab]} options={{ title: tab }} />
        {pages()}
      </Stack.Navigator>
    );
  };
}
const STACKS = { Today: tabStack('Today'), Plants: tabStack('Plants'), Journal: tabStack('Journal'), More: tabStack('More') };

export type ShellProps = {
  // Null until the first refresh names the member.
  member: Member | null;
  theme: Theme;
  // A saved state to bring back (Android recreated the activity), and where to save the next one.
  initialState?: NavigationState;
  onState(state: NavigationState | undefined): void;
  // Called when the member or role changes, before the stacks change.
  onMemberChanged(): void;
  // Notification taps that open Today (W6-P16).
  subscribeOpenHome(listener: () => void): () => void;
};

// W6-P16: Today's view at its root; pages over Today close (their questions ask first); other tabs keep theirs.
export function openHome() {
  if (!navigation.isReady()) return;
  navigation.dispatch(CommonActions.navigate({ name: 'Today' }));
  const today = (navigation.getRootState()?.routes.find((route) => route.name === 'Today')?.state);
  if (today && (today.routes?.length ?? 0) > 1) navigation.dispatch({ ...StackActions.popToTop(), target: today.key });
}

export function Shell({ member, theme, initialState, onState, onMemberChanged, subscribeOpenHome }: ShellProps) {
  const shell = useShell();
  const tabs = tabsFor(member?.role ?? null);
  const previous = useRef<Member | null>(member);
  const pendingTab = useRef<string | null>(null);
  const [ready, setReady] = useState(false);

  // W6-P17: another member starts every tab at its root; a role change closes what the new role may not open.
  useEffect(() => {
    const before = previous.current;
    previous.current = member;
    if (!ready || !before || !member || (before.id === member.id && before.role === member.role)) return;
    onMemberChanged();
    const state = navigation.getRootState();
    if (before.id !== member.id || !state) navigation.reset({ index: 0, routes: [{ name: 'Today' }] });
    else navigation.reset(filterState(state, member.role) as never);
  }, [member, ready, onMemberChanged]);

  useEffect(() => subscribeOpenHome(() => {
    if (shell.saving.size > 0) pendingTab.current = 'home';
    else openHome();
  }), [shell, subscribeOpenHome]);

  // W6-P21: a tab change asked for during a save waits for it to end; a second request replaces the first.
  useEffect(() => shell.subscribe(() => {
    if (shell.saving.size > 0 || !pendingTab.current) return;
    const target = pendingTab.current;
    pendingTab.current = null;
    if (target === 'home') openHome();
    else navigation.dispatch(CommonActions.navigate({ name: target }));
  }), [shell]);

  return (
    <NavigationContainer
      ref={navigation} theme={theme} initialState={initialState as never}
      onReady={() => setReady(true)} onStateChange={(state) => onState(state as NavigationState | undefined)}>
      <Tab.Navigator
        backBehavior="firstRoute"
        screenOptions={({ route }) => ({
          headerShown: false,
          tabBarIcon: ({ color, size }) => <MaterialCommunityIcons name={ICONS[route.name as TabName]} color={color} size={size} />,
          tabBarButtonTestID: `tab-${route.name}`,
          tabBarAccessibilityLabel: route.name,
        })}
        screenListeners={({ route }) => ({
          tabPress: (event) => {
            if (shell.saving.size === 0) return;
            event.preventDefault();
            pendingTab.current = route.name;
          },
        })}>
        {tabs.map((tab) => <Tab.Screen key={tab} name={tab} component={STACKS[tab]} />)}
      </Tab.Navigator>
    </NavigationContainer>
  );
}
