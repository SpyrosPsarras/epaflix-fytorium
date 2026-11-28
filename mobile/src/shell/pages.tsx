import { useCallback, useEffect, useRef, useState } from 'react';
import { BackHandler, FlatList, ScrollView, View } from 'react-native';
import { CommonActions, StackActions, useFocusEffect, useNavigation, useRoute, type NavigationProp, type RouteProp } from '@react-navigation/native';
import type { NativeStackHeaderProps } from '@react-navigation/native-stack';
import { Image } from 'expo-image';
import { Appbar, Button, List, Text, TextInput, useTheme } from 'react-native-paper';
import { AboutPage } from '../screens/AboutPage';
import { LogoutDialog } from '../screens/LogoutDialog';
import { parsePlants, useCare, useDeps, useDraft, useTabShown, type Plant } from './context';
import { LeaveGuard, QuestionDialog, useShell, type Hidden } from './guards';
import { navigation as root, rootToTop, stackOf } from './actions';
import type { RootParams, StackParams } from './routes';

type Nav = NavigationProp<StackParams>;

// W6-P10: every page above a tab root has a Back arrow that does what system Back does.
export function PageHeader({ back, options, navigation, route }: NativeStackHeaderProps) {
  const title = typeof options.title === 'string' ? options.title : route.name;
  return (
    <Appbar.Header>
      {/* W6-P24: a 48 dp target; Paper's default is 40 dp, and its touchable cannot reach past that. */}
      {back ? <Appbar.BackAction accessibilityLabel="Back" onPress={() => navigation.goBack()} style={{ width: 48, height: 48, margin: 4 }} /> : null}
      <Appbar.Content title={title} titleStyle={{}} accessibilityRole="header" />
      {options.headerRight?.({ canGoBack: Boolean(back) })}
    </Appbar.Header>
  );
}

// A shell signal in the route's params, seen only when it changes, not when a restored state brings it back.
function useSignal(name: keyof NonNullable<RootParams>, act: () => void) {
  const value = (useRoute().params as RootParams)?.[name];
  const seen = useRef(value);
  useEffect(() => {
    if (value === seen.current) return;
    seen.current = value;
    act();
  });
}

type Scrollable = { scrollToOffset?(options: { offset: number; animated: boolean }): void; scrollTo?(options: { y: number; animated: boolean }): void };

// W6-P4: a list keeps its place while its page is in a stack, across process death and activity recreation.
// W6-P6: a reselect at its root scrolls it to the top and saves that, since a scroll made in code sends no drag event.
function useSavedScroll() {
  const [offset, setOffset] = useDraft('scroll', 0);
  const ref = useRef<Scrollable>(null);
  useSignal('top', () => {
    if (ref.current?.scrollToOffset) ref.current.scrollToOffset({ offset: 0, animated: true });
    else ref.current?.scrollTo?.({ y: 0, animated: true });
    setOffset(0);
  });
  const save = (event: { nativeEvent: { contentOffset: { y: number } } }) => setOffset(event.nativeEvent.contentOffset.y);
  return { ref: ref as never, contentOffset: { x: 0, y: offset }, onScrollEndDrag: save, onMomentumScrollEnd: save };
}

// W6-P22: a list reads on entry and each time it is shown again (a tab tap, Back to it); nothing moves while it
// stays in view. Today reads its task lists; Plants, Find and the pickers read the plant list alone.
function useReadOnShow(read: 'all' | 'plants', onEntry: boolean) {
  const { care } = useDeps();
  const shown = useRef(false);
  useFocusEffect(useCallback(() => {
    if (shown.current || onEntry) void (read === 'all' ? care.refresh() : care.refreshPlants());
    shown.current = true;
    // Resume and reconnect run the full refresh; a shown plant list also reads alone (Kotlin PlantsScreen.reload).
    return read === 'plants' ? care.onReturn(() => void care.refreshPlants()) : undefined;
  }, [care, read, onEntry]));
}

function PlantRows({ plants, open, header, read = 'plants', onEntry = true }: {
  plants: Plant[]; open(plant: Plant): void; header?: React.ReactElement; read?: 'all' | 'plants'; onEntry?: boolean;
}) {
  useReadOnShow(read, onEntry);
  return (
    <FlatList
      data={plants} keyExtractor={(plant) => plant.id} ListHeaderComponent={header} testID="plant-rows" {...useSavedScroll()}
      // Find opens with the keyboard up; the first tap on a result opens it instead of only closing the keyboard.
      keyboardShouldPersistTaps="handled"
      renderItem={({ item }) => (
        <List.Item title={item.name} onPress={() => open(item)} accessibilityRole="button" style={{ minHeight: 56 }} />
      )}
    />
  );
}

// shortcut: W3 holding pages; the Today, Plants, Journal and More waves replace their bodies, not their navigation.
// Offline, or Fytorium not answering, with the time of the last good read (W2; Find shows it too, W6-P13).
function StatusLine() {
  const care = useCare();
  const updated = care.lastUpdated ? new Date(care.lastUpdated).toTimeString().slice(0, 5) : null;
  const status = care.offline ? `Offline${updated ? ` · updated ${updated}` : ''}` : care.serverUnavailable ? "Fytorium isn't responding" : '';
  return <Text testID="status-line" style={{ marginHorizontal: 16, minHeight: 20 }}>{status}</Text>;
}

export function TodayRoot() {
  const navigation = useNavigation<Nav>();
  const care = useCare();
  const deps = useDeps();
  const shell = useShell();
  // Today shown at start is covered by App's start refresh; restored elsewhere, Today reads when first shown.
  const [startScreen] = useState(() => navigation.isFocused());
  // W6-P9: with a form with changes in a hidden tab, Back here keeps the task instead of letting Android finish it.
  useFocusEffect(useCallback(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (shell.dirty.size === 0) return false;
      deps.moveTaskToBack();
      return true;
    });
    return () => subscription.remove();
  }, [deps, shell]));
  const plants = parsePlants(care.plants);
  const writer = care.member?.role === 'owner' || care.member?.role === 'family';
  return (
    <View style={{ flex: 1 }} testID="today-root">
      <StatusLine />
      <PlantRows
        plants={plants} read="all" onEntry={!startScreen}
        open={(plant) => navigation.navigate('Plant', { id: plant.id })}
        header={(
          <View style={{ padding: 16, gap: 8 }}>
            <Button mode="outlined" onPress={() => navigation.navigate('Find')} contentStyle={{ minHeight: 48 }}>Find a plant</Button>
            {writer && <Button mode="outlined" onPress={() => navigation.navigate('Add')} contentStyle={{ minHeight: 48 }}>Add plant</Button>}
            {care.member?.role === 'owner' && (
              <Button mode="outlined" onPress={() => navigation.navigate('Planta')} contentStyle={{ minHeight: 48 }}>Import from Planta</Button>
            )}
            {writer && <Button mode="outlined" onPress={() => navigation.navigate('Weather')} contentStyle={{ minHeight: 48 }}>Weather and feed</Button>}
          </View>
        )}
      />
    </View>
  );
}

export function PlantsRoot() {
  const navigation = useNavigation<Nav>();
  const care = useCare();
  const [query, setQuery] = useDraft('query', '');
  useSignal('clear', () => setQuery(''));
  const plants = parsePlants(care.plants).filter((plant) => plant.name.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <View style={{ flex: 1 }} testID="plants-root">
      <TextInput label="Search plants" testID="plants-search" value={query} onChangeText={setQuery} style={{ margin: 16 }} />
      <PlantRows plants={plants} open={(plant) => navigation.navigate('Plant', { id: plant.id })} />
    </View>
  );
}

export function JournalRoot() {
  const navigation = useNavigation<Nav>();
  const plants = parsePlants(useCare().plants);
  return (
    <View style={{ flex: 1 }} testID="journal-root">
      <PlantRows
        plants={plants} open={(plant) => navigation.navigate('Plant', { id: plant.id })}
        header={<Button mode="outlined" onPress={() => navigation.navigate('JournalPick')} style={{ margin: 16 }} contentStyle={{ minHeight: 48 }}>Add a photo</Button>}
      />
    </View>
  );
}

type MoreRow = { route: keyof StackParams; title: string; description: string };

// W5a-P1 to P5: rows under a header that says who is logged in; Log out asks first.
export function MoreRoot() {
  const navigation = useNavigation<Nav>();
  const deps = useDeps();
  const care = useCare();
  const role = care.member?.role;
  const shell = useShell();
  // An open question survives Android recreating the activity, as Kotlin's saved `confirmLogout` does.
  const [confirming, setConfirming] = useDraft('confirming', false);
  const [hidden, setHidden] = useDraft<[string, Hidden] | null>('hidden', null);
  const [answered, setAnswered] = useDraft<string[]>('answered', []);
  const focused = useTabShown();
  // W6-P26: each form with changes in another tab asks its own question, in turn, before log out drops it.
  const askNext = (done: string[]) => {
    const next = [...shell.dirty].find(([page]) => !done.includes(page)) ?? null;
    setHidden(next);
    if (!next) deps.logout();
  };
  let name: string | null = null;
  try { name = care.identity ? JSON.parse(care.identity)?.member?.display_name ?? null : null; } catch { name = null; }
  const rows: MoreRow[] = role === 'sitter'
    ? [{ route: 'Notifications', title: 'Notifications', description: 'Reminders, alerts and devices' }]
    : role ? [
      { route: 'Notifications', title: 'Notifications', description: 'Reminders, alerts and devices' },
      { route: 'Weather', title: 'Weather and feed', description: 'Location and cards on Today' },
      ...(role === 'owner' ? [{ route: 'Members', title: 'Members', description: 'People and invites' } as MoreRow] : []),
      { route: 'CustomTasks', title: 'Custom tasks', description: 'Your own repeating tasks' },
      ...(role === 'owner' ? [{ route: 'Planta', title: 'Import from Planta', description: 'Bring plants over from Planta' } as MoreRow] : []),
      { route: 'Unmatched', title: 'Species without care data', description: 'Species with no care data from sources' },
    ] : [];
  return (
    <ScrollView testID="more-root" {...useSavedScroll()}>
      {role && (
        <View style={{ padding: 16 }} accessible>
          {name && <Text variant="titleLarge">{name}</Text>}
          <Text>{role === 'owner' ? 'Owner' : role === 'family' ? 'Family' : 'Sitter'}</Text>
        </View>
      )}
      {rows.map((row) => (
        <List.Item key={row.route} title={row.title} description={row.description} accessibilityRole="button" style={{ minHeight: 72 }}
          onPress={() => navigation.navigate(row.route as never)} />
      ))}
      <List.Item title="About" description="Version, data sources and diagnostics" accessibilityRole="button" style={{ minHeight: 72 }}
        onPress={() => navigation.navigate('About')} />
      <Button onPress={() => setConfirming(true)} disabled={deps.busy} style={{ alignSelf: 'flex-start', margin: 8 }} contentStyle={{ minHeight: 48 }}>Log out</Button>
      <LogoutDialog
        visible={confirming && focused} online={deps.online} onDismiss={() => setConfirming(false)}
        onConfirm={() => {
          setConfirming(false);
          setAnswered([]);
          askNext([]);
        }} />
      <QuestionDialog
        question={focused ? hidden?.[1].question ?? null : null}
        onStay={() => { const tab = hidden?.[1].tab; setHidden(null); if (tab) navigation.navigate(tab as never); }}
        onLeave={() => {
          const done = hidden ? [...answered, hidden[0]] : answered;
          setAnswered(done);
          askNext(done);
        }} />
    </ScrollView>
  );
}

export function AboutRoute() {
  const deps = useDeps();
  return (
    <ScrollView contentContainerStyle={{ padding: 16 }}>
      <AboutPage appVersion={deps.appVersion} serverVersion={deps.serverVersion} onCopyDiagnostics={deps.copyDiagnostics} />
    </ScrollView>
  );
}

export function PlantPage() {
  const route = useRoute<RouteProp<StackParams, 'Plant'>>();
  const navigation = useNavigation<Nav>();
  const deps = useDeps();
  const care = useCare();
  const { colors } = useTheme();
  const [plant, setPlant] = useState<Plant | null>(() => parsePlants(care.plants).find((row) => row.id === route.params.id) ?? null);
  const [cover, setCover] = useState<{ id: string; uri: string | null } | null>(null);
  // W6-P22: the plant reads each time its page is shown, and while shown when the app comes back to the
  // foreground or online, so a change made elsewhere shows.
  useFocusEffect(useCallback(() => {
    let current = true;
    const read = () => void deps.care.plant(route.params.id).then((row) => {
      if (!current || !row) return;
      const [fresh] = parsePlants(`[${row.json}]`);
      setPlant(fresh);
    });
    read();
    const stop = deps.care.onReturn(read);
    return () => { current = false; stop(); };
  }, [deps.care, route.params.id]));
  useEffect(() => {
    let current = true;
    const id = plant?.coverPhotoId;
    if (id && care.member) void deps.photos.load(care.member, id, 'display').then((uri) => { if (current) setCover({ id, uri }); });
    return () => { current = false; };
  }, [deps.photos, care.member, plant?.coverPhotoId]);
  // Only the current cover shows; a cover removed elsewhere goes with the read that drops it.
  const coverUri = cover && cover.id === plant?.coverPhotoId ? cover.uri : null;
  useEffect(() => { navigation.setOptions({ title: plant?.name ?? 'Plant' }); }, [navigation, plant?.name]);
  const writer = care.member?.role === 'owner' || care.member?.role === 'family';
  return (
    <ScrollView testID={`plant-${route.params.id}`} contentContainerStyle={{ padding: 16, gap: 12 }}>
      <View style={{ aspectRatio: 4 / 3, backgroundColor: colors.surfaceVariant }}>
        {coverUri && <Image source={{ uri: coverUri }} cachePolicy="memory" style={{ flex: 1 }} accessibilityLabel={`Photo of ${plant?.name ?? 'the plant'}`} />}
      </View>
      <Text variant="headlineSmall">{plant?.name ?? 'Plant'}</Text>
      {writer && <Button mode="outlined" onPress={() => navigation.navigate('PlantEdit', { id: route.params.id })} contentStyle={{ minHeight: 48 }}>Edit</Button>}
    </ScrollView>
  );
}

// A stand-in form with a draft, so the shell's discard and restore rules hold before the real forms arrive.
export function DraftForm({ label }: { label: string }) {
  const [text, setText] = useDraft('text', '');
  return (
    <View style={{ padding: 16, gap: 12 }}>
      <TextInput label={label} testID="draft-field" value={text} onChangeText={setText} />
      <LeaveGuard dirty={text.length > 0} />
    </View>
  );
}

export function PlantEdit() {
  return <DraftForm label="Nickname" />;
}

export function JournalPick() {
  const navigation = useNavigation<Nav>();
  return <PlantRows plants={parsePlants(useCare().plants)} open={(plant) => navigation.navigate('JournalAdd', { plantId: plant.id })} />;
}

export function JournalAdd() {
  return <DraftForm label="Note" />;
}

export function StubPage() {
  return (
    <View style={{ padding: 16 }}>
      <Text>This page arrives in a later update.</Text>
    </View>
  );
}

// Holding pages with only the links later waves keep (W6-P12, W6-P15): Planta opens an imported plant and Sites,
// and "View imported plants" closes the review and shows the Plants grid at the top without its search.
export function PlantaStub() {
  const navigation = useNavigation<Nav>();
  const route = useRoute();
  const shell = useShell();
  const first = parsePlants(useCare().plants)[0];
  const [asking, setAsking] = useDraft<[string, Hidden] | null>('asking', null);
  const [answered, setAnswered] = useDraft<string[]>('answered', []);
  const focused = useTabShown();
  const viewImported = () => {
    const plants = stackOf('Plants');
    // Planta leaves its stack with every page above it (Kotlin showAllPlants).
    const own = navigation.getState();
    const at = own?.routes.findIndex((entry) => entry.key === route.key) ?? -1;
    if (own && at >= 0) root.dispatch({ ...StackActions.pop(own.routes.length - at), target: own.key });
    if (plants?.key && plants.routes.length > 1) root.dispatch({ type: 'POP_TO_TOP', target: plants.key });
    rootToTop('Plants', true);
    root.dispatch(CommonActions.navigate('Plants'));
  };
  // Every form the action would close asks first: in the Plants stack, and above Planta in its own (W6-P18).
  const plantsForms = () => {
    const own = navigation.getState()?.routes ?? [];
    const above = new Set(own.slice(own.findIndex((entry) => entry.key === route.key) + 1).map((entry) => entry.key));
    return [...shell.dirty].filter(([page, entry]) => entry.tab === 'Plants' || above.has(page));
  };
  // Each form asks in turn; nothing closes until every one was answered with leave, and Stay keeps them all.
  const askNext = (done: string[]) => {
    const next = plantsForms().find(([page]) => !done.includes(page)) ?? null;
    setAsking(next);
    if (next) return;
    done.forEach((page) => shell.discarded.add(page));
    viewImported();
  };
  return (
    <View style={{ padding: 16, gap: 12 }}>
      <StubPage />
      {first && <Button mode="outlined" onPress={() => navigation.navigate('Plant', { id: first.id })} contentStyle={{ minHeight: 48 }}>Open imported plant</Button>}
      <Button mode="outlined" onPress={() => navigation.navigate('Sites')} contentStyle={{ minHeight: 48 }}>Check sites</Button>
      <Button mode="outlined" contentStyle={{ minHeight: 48 }}
        onPress={() => shell.request({ kind: 'run', run: () => {
          // A request held for a save is dropped if the review left its stack meanwhile.
          if (!navigation.getState()?.routes.some((entry) => entry.key === route.key)) return;
          setAnswered([]);
          askNext([]);
        } })}>
        View imported plants
      </Button>
      <QuestionDialog
        question={focused ? asking?.[1].question ?? null : null}
        onStay={() => setAsking(null)}
        onLeave={() => {
          const done = asking ? [...answered, asking[0]] : answered;
          setAnswered(done);
          askNext(done);
        }} />
    </View>
  );
}

export function UnmatchedStub() {
  const navigation = useNavigation<Nav>();
  return (
    <View style={{ padding: 16, gap: 12 }}>
      <StubPage />
      <Button mode="outlined" onPress={() => navigation.navigate('Species', { key: 'synthetic-species' })} contentStyle={{ minHeight: 48 }}>Synthetic species</Button>
    </View>
  );
}

export function FindPage() {
  const navigation = useNavigation<Nav>();
  const [query, setQuery] = useDraft('query', '');
  // W6-P13 focuses the field once, when Find opens; a restored Find keeps a keyboard the user closed closed.
  const [focusedOnce, setFocusedOnce] = useDraft('focused', false);
  const plants = parsePlants(useCare().plants).filter((plant) => plant.name.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <View style={{ flex: 1 }}>
      <StatusLine />
      <TextInput label="Search plants" testID="find-search" value={query} onChangeText={setQuery} autoFocus={!focusedOnce}
        onFocus={() => { if (!focusedOnce) setFocusedOnce(true); }} style={{ margin: 16 }} />
      <PlantRows plants={plants} open={(plant) => navigation.navigate('Plant', { id: plant.id })} />
    </View>
  );
}

