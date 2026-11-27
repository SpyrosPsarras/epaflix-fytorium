import { useCallback, useEffect, useRef, useState } from 'react';
import { BackHandler, FlatList, ScrollView, View } from 'react-native';
import { useFocusEffect, useNavigation, useRoute, useScrollToTop, type NavigationProp, type RouteProp } from '@react-navigation/native';
import type { NativeStackHeaderProps } from '@react-navigation/native-stack';
import { Image } from 'expo-image';
import { Appbar, Button, List, Text, TextInput, useTheme } from 'react-native-paper';
import { AboutPage } from '../screens/AboutPage';
import { LogoutDialog } from '../screens/LogoutDialog';
import { parsePlants, useCare, useDeps, useDraft, useMemberKey, type Plant } from './context';
import { LeaveGuard, QuestionDialog, useShell, type Hidden } from './guards';
import { pageKey, type StackParams } from './routes';

type Nav = NavigationProp<StackParams>;

// W6-P10: every page above a tab root has a Back arrow that does what system Back does.
export function PageHeader({ back, options, navigation, route }: NativeStackHeaderProps) {
  const title = typeof options.title === 'string' ? options.title : route.name;
  return (
    <Appbar.Header>
      {back ? <Appbar.BackAction accessibilityLabel="Back" onPress={() => navigation.goBack()} /> : null}
      <Appbar.Content title={title} titleStyle={{}} accessibilityRole="header" />
      {options.headerRight?.({ canGoBack: Boolean(back) })}
    </Appbar.Header>
  );
}

function PlantRows({ plants, open, header }: { plants: Plant[]; open(plant: Plant): void; header?: React.ReactElement }) {
  const list = useRef<FlatList<Plant>>(null);
  // W6-P6: reselecting the tab at its root scrolls to the top.
  useScrollToTop(list);
  return (
    <FlatList
      ref={list} data={plants} keyExtractor={(plant) => plant.id} ListHeaderComponent={header}
      renderItem={({ item }) => (
        <List.Item title={item.name} onPress={() => open(item)} accessibilityRole="button" style={{ minHeight: 56 }} />
      )}
    />
  );
}

// shortcut: W3 holding pages; the Today, Plants, Journal and More waves replace their bodies, not their navigation.
export function TodayRoot() {
  const navigation = useNavigation<Nav>();
  const care = useCare();
  const deps = useDeps();
  // W6-P9: with a draft waiting in a hidden tab, Back here keeps the task instead of letting Android finish it.
  useFocusEffect(useCallback(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (!deps.drafts.any()) return false;
      deps.moveTaskToBack();
      return true;
    });
    return () => subscription.remove();
  }, [deps]));
  const plants = parsePlants(care.plants);
  const writer = care.member?.role === 'owner' || care.member?.role === 'family';
  const updated = care.lastUpdated ? new Date(care.lastUpdated).toTimeString().slice(0, 5) : null;
  const status = care.offline ? `Offline${updated ? ` · updated ${updated}` : ''}` : care.serverUnavailable ? "Fytorium isn't responding" : '';
  return (
    <View style={{ flex: 1 }} testID="today-root">
      <Text testID="status-line" style={{ marginHorizontal: 16, minHeight: 20 }}>{status}</Text>
      <PlantRows
        plants={plants}
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
  const key = pageKey(useMemberKey(), 'Plants', 'root');
  const [query, setQuery] = useDraft(key, '');
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
  const [confirming, setConfirming] = useState(false);
  const [hidden, setHidden] = useState<Hidden | null>(null);
  const scroll = useRef<React.ComponentRef<typeof ScrollView>>(null);
  useScrollToTop(scroll as never);
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
    <ScrollView ref={scroll} testID="more-root">
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
        visible={confirming} online={deps.online} onDismiss={() => setConfirming(false)}
        onConfirm={() => {
          setConfirming(false);
          // W6-P26: a form with changes in another tab asks its own question before log out drops it.
          const waiting = shell.dirty.values().next().value ?? null;
          if (waiting) setHidden(waiting); else deps.logout();
        }} />
      <QuestionDialog
        question={hidden?.question ?? null}
        onStay={() => { const tab = hidden?.tab; setHidden(null); if (tab) navigation.navigate(tab as never); }}
        onLeave={() => { setHidden(null); deps.logout(); }} />
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
  const [cover, setCover] = useState<string | null>(null);
  useEffect(() => {
    let current = true;
    void deps.care.plant(route.params.id).then((row) => {
      if (!current || !row) return;
      const [read] = parsePlants(`[${row.json}]`);
      setPlant(read);
    });
    return () => { current = false; };
  }, [deps.care, route.params.id]);
  useEffect(() => {
    let current = true;
    if (plant?.coverPhotoId && care.member) void deps.photos.load(care.member, plant.coverPhotoId, 'display').then((uri) => { if (current) setCover(uri); });
    return () => { current = false; };
  }, [deps.photos, care.member, plant?.coverPhotoId]);
  useEffect(() => { navigation.setOptions({ title: plant?.name ?? 'Plant' }); }, [navigation, plant?.name]);
  const writer = care.member?.role === 'owner' || care.member?.role === 'family';
  return (
    <ScrollView testID={`plant-${route.params.id}`} contentContainerStyle={{ padding: 16, gap: 12 }}>
      <View style={{ aspectRatio: 4 / 3, backgroundColor: colors.surfaceVariant }}>
        {cover && <Image source={{ uri: cover }} cachePolicy="memory" style={{ flex: 1 }} accessibilityLabel={`Photo of ${plant?.name ?? 'the plant'}`} />}
      </View>
      <Text variant="headlineSmall">{plant?.name ?? 'Plant'}</Text>
      {writer && <Button mode="outlined" onPress={() => navigation.navigate('PlantEdit', { id: route.params.id })} contentStyle={{ minHeight: 48 }}>Edit</Button>}
    </ScrollView>
  );
}

// A stand-in form with a draft, so the shell's discard and restore rules hold before the real forms arrive.
export function DraftForm({ label }: { label: string }) {
  const route = useRoute();
  const [text, setText] = useDraft(pageKey(useMemberKey(), 'form', route.key), '');
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

export function FindPage() {
  const navigation = useNavigation<Nav>();
  const [query, setQuery] = useState('');
  const plants = parsePlants(useCare().plants).filter((plant) => plant.name.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <View style={{ flex: 1 }}>
      <TextInput label="Search plants" testID="find-search" value={query} onChangeText={setQuery} autoFocus style={{ margin: 16 }} />
      <PlantRows plants={plants} open={(plant) => navigation.navigate('Plant', { id: plant.id })} />
    </View>
  );
}

