import { useEffect, type ReactNode } from 'react';
import { BackHandler, StyleSheet, View } from 'react-native';

// Keeps the app mounted under a full-screen stop: nothing under it can be touched, read by a screen reader or
// reached with Back, and its state is still there when the stop clears.
export function Stoppable({ stop, children }: { stop: ReactNode | null; children: ReactNode }) {
  const stopped = stop !== null;
  useEffect(() => {
    if (!stopped) return undefined;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => { BackHandler.exitApp(); return true; });
    return () => subscription.remove();
  }, [stopped]);
  return (
    <View style={{ flex: 1 }}>
      <View style={{ flex: 1 }} pointerEvents={stopped ? 'none' : 'auto'}
        importantForAccessibility={stopped ? 'no-hide-descendants' : 'auto'} accessibilityElementsHidden={stopped}>
        {children}
      </View>
      {stopped && <View style={StyleSheet.absoluteFill}>{stop}</View>}
    </View>
  );
}
