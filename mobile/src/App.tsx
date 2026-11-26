import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, View, useColorScheme } from 'react-native';
import { palette } from './theme';

export default function App() {
  const colors = palette(useColorScheme());
  return (
    <View style={[styles.root, { backgroundColor: colors.background }]}>
      <Text accessibilityRole="header" style={[styles.title, { color: colors.onBackground }]}>Fytorium</Text>
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  title: { fontSize: 28, fontWeight: '600' },
});
