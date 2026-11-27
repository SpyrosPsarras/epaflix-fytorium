import { useEffect, type ReactNode } from 'react';
import { BackHandler, Keyboard, StyleSheet, View } from 'react-native';
import { Portal } from 'react-native-paper';

// Keeps the app mounted under a full-screen stop: nothing under it can be touched, typed into, read by a screen
// reader or reached with Back, including its dialogs, and its state is still there when the stop clears.
export function Stoppable({ stop, children }: { stop: ReactNode | null; children: ReactNode }) {
  const stopped = stop !== null;
  useEffect(() => {
    if (!stopped) return undefined;
    Keyboard.dismiss();
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => { BackHandler.exitApp(); return true; });
    return () => subscription.remove();
  }, [stopped]);
  return (
    <View style={{ flex: 1 }}>
      <View style={{ flex: 1 }} pointerEvents={stopped ? 'none' : 'auto'}
        importantForAccessibility={stopped ? 'no-hide-descendants' : 'auto'} accessibilityElementsHidden={stopped}>
        {/* The shell's dialogs render here, under the stop, not at the app's own portal host above it. */}
        <Portal.Host>{children}</Portal.Host>
      </View>
      {stopped && <View style={StyleSheet.absoluteFill}>{stop}</View>}
    </View>
  );
}
