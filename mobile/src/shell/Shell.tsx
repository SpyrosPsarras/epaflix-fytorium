import { useEffect, useRef, useState } from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { CommonActions, NavigationContainer, type NavigationState, type Theme } from '@react-navigation/native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { memberKey, type Member } from '../data/cache';
import { useShell } from './guards';
import { navigation, popToRoot, reselect, stackOf } from './actions';
import { TabContext, useDeps } from './context';
import {
  AboutRoute, FindPage, JournalAdd, JournalPick, JournalRoot, MoreRoot, PageHeader, PlantEdit, PlantPage, PlantaStub, PlantsRoot, StubPage, TodayRoot, UnmatchedStub,
} from './pages';
import { filterState, pageKey, routeKeys, tabsFor, TAB_ROOTS, type StackParams, type TabName } from './routes';

export { navigation };

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
      <Stack.Screen name="Planta" component={PlantaStub} options={{ title: 'Import from Planta' }} />
      <Stack.Screen name="Weather" component={StubPage} options={{ title: 'Weather and feed' }} />
      <Stack.Screen name="Notifications" component={StubPage} options={{ title: 'Notifications' }} />
      <Stack.Screen name="Members" component={StubPage} options={{ title: 'Members' }} />
      <Stack.Screen name="CustomTasks" component={StubPage} options={{ title: 'Custom tasks' }} />
      <Stack.Screen name="Unmatched" component={UnmatchedStub} options={{ title: 'Species without care data' }} />
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
      <TabContext.Provider value={tab}>
        <Stack.Navigator screenOptions={{ header: (props) => <PageHeader {...props} /> }}>
          <Stack.Screen name={TAB_ROOTS[tab] as keyof StackParams} component={ROOTS[tab]} options={{ title: tab }} />
          {pages()}
        </Stack.Navigator>
      </TabContext.Provider>
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
  // Notification taps that open Today (W6-P16).
  subscribeOpenHome(listener: () => void): () => void;
};

type Intent = { kind: 'home' } | { kind: 'tab'; tab: string; reselect: boolean };

function run(intent: Intent) {
  if (!navigation.isReady()) return;
  if (intent.kind === 'home') {
    navigation.dispatch(CommonActions.navigate('Today'));
    popToRoot('Today');
  } else if (intent.reselect) {
    reselect(intent.tab);
  } else {
    navigation.dispatch(CommonActions.navigate(intent.tab));
  }
}

export function Shell({ member, theme, initialState, onState, subscribeOpenHome }: ShellProps) {
  const shell = useShell();
  const { drafts } = useDeps();
  const pending = useRef<Intent | null>(null);
  const shown = useRef(new Set<string>());
  // The member the navigator was built for. A new member or role builds a new one (W6-P17): no question can stop it,
  // and no page keeps state from before; for the same member only the pages the new role may open come along.
  const [applied, setApplied] = useState({ member, version: 0, initialState });
  const tabs = tabsFor(applied.member?.role ?? null);

  if (applied.member?.id !== member?.id || applied.member?.role !== member?.role) {
    const same = applied.member && member && applied.member.id === member.id;
    const state = same && navigation.isReady() ? navigation.getRootState() : undefined;
    setApplied({ member, version: applied.version + 1, initialState: state ? (filterState(state, member!.role) as NavigationState) : undefined });
  }

  const request = (intent: Intent) => {
    if (shell.saving.size > 0) pending.current = intent;
    else run(intent);
  };

  useEffect(() => subscribeOpenHome(() => request({ kind: 'home' })));

  // W6-P21: what was asked for during a save happens when it ends; a second request replaces the first.
  useEffect(() => shell.subscribe(() => {
    if (shell.saving.size > 0 || !pending.current) return;
    const intent = pending.current;
    pending.current = null;
    run(intent);
  }), [shell]);

  return (
    <NavigationContainer
      key={`${applied.member ? memberKey(applied.member) : 'none'}-${applied.version}`}
      ref={navigation} theme={theme} initialState={applied.initialState as never}
      onReady={() => {
        shown.current = routeKeys(navigation.getRootState());
        // The first screen is saved too, so its scroll or draft comes back even if nothing was opened yet (N3).
        onState(navigation.getRootState() as NavigationState);
      }}
      onStateChange={(state) => {
        // W6-P4: a page's saved state goes when its route leaves the stack, never when Android only destroys the view.
        const now = routeKeys(state);
        const owner = applied.member ? memberKey(applied.member) : 'none';
        shown.current.forEach((key) => { if (!now.has(key)) drafts.removeUnder(pageKey(owner, key)); });
        shown.current = now;
        onState(state as NavigationState | undefined);
      }}>
      <Tab.Navigator
        backBehavior="firstRoute"
        screenOptions={({ route }) => ({
          headerShown: false,
          // After a restore every tab mounts, so a restored form in a tab not yet shown still asks before log out (W6-P26).
          lazy: !applied.initialState,
          tabBarIcon: ({ color, size }) => <MaterialCommunityIcons name={ICONS[route.name as TabName]} color={color} size={size} />,
          tabBarButtonTestID: `tab-${route.name}`,
          tabBarAccessibilityLabel: route.name,
        })}
        screenListeners={({ navigation: tabNavigation, route }) => ({
          tabPress: (event) => {
            const again = tabNavigation.isFocused();
            if (shell.saving.size > 0) {
              event.preventDefault();
              pending.current = { kind: 'tab', tab: route.name, reselect: again };
            } else if (again && (stackOf(route.name)?.routes?.length ?? 1) === 1) {
              reselect(route.name);
            }
          },
        })}>
        {tabs.map((tab) => <Tab.Screen key={tab} name={tab} component={STACKS[tab]} />)}
      </Tab.Navigator>
    </NavigationContainer>
  );
}
