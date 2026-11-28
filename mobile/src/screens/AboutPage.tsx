import { Linking, Pressable, View } from 'react-native';
import { Button, Text, useTheme } from 'react-native-paper';

const sources: [string, string, string][] = [
  ['GBIF', 'Species and names · CC BY 4.0', 'https://www.gbif.org/'],
  ['Wikidata', 'Names · CC0', 'https://www.wikidata.org/'],
  ['NorTaxa', 'Norwegian names', 'https://nortaxa.artsdatabanken.no/'],
  ['Open-Meteo', 'Weather and climate', 'https://open-meteo.com/'],
  ['Open Plantbook', 'Care data', 'https://open.plantbook.io/'],
  ['Perenual', 'Care data', 'https://perenual.com/'],
  ['Pl@ntNet', 'Identify from photos', 'https://plantnet.org/'],
];

// W5a-P10.
export function AboutPage({ appVersion, serverVersion, onCopyDiagnostics }: { appVersion: string; serverVersion: string | null; onCopyDiagnostics(): void }) {
  const { colors } = useTheme();
  return (
    <View style={{ gap: 12 }}>
      <Text>Fytorium {appVersion}</Text>
      <Text style={{ color: colors.onSurfaceVariant }}>{serverVersion ? `Server ${serverVersion}` : 'Server version not loaded yet'}</Text>
      <Button mode="outlined" onPress={onCopyDiagnostics} contentStyle={{ minHeight: 48 }}>Copy diagnostics</Button>
      <Text style={{ color: colors.onSurfaceVariant }}>
        Diagnostics hold the app version and recent error types with their times, nothing about your plants or account. They stay on this phone until you copy them.
      </Text>
      <Text variant="titleMedium" accessibilityRole="header">Data sources</Text>
      {sources.map(([name, what, url]) => (
        <Pressable key={name} accessibilityRole="button" accessibilityHint={`Open ${name}`} onPress={() => Linking.openURL(url)}
          style={{ minHeight: 56, justifyContent: 'center', paddingVertical: 8 }}>
          <Text variant="bodyLarge">{name}</Text>
          <Text variant="bodyMedium" style={{ color: colors.onSurfaceVariant }}>{what}</Text>
        </Pressable>
      ))}
      <Button onPress={() => Linking.openURL('https://github.com/SpyrosPsarras/epaflix-fytorium')} contentStyle={{ minHeight: 48 }}>Source code</Button>
    </View>
  );
}
