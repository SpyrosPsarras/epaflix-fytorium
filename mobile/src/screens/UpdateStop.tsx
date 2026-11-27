import { useState } from 'react';
import { Linking, Platform, View } from 'react-native';
import { Button, Text } from 'react-native-paper';
import { LogoutDialog } from './LogoutDialog';

const UPDATE_URL = Platform.OS === 'ios' ? 'itms-beta://' : 'https://github.com/SpyrosPsarras/epaflix-fytorium/releases/latest';

// W5a-P11 and P12.
export function UpdateStop(props: {
  appVersion: string; minimum: string | null; message: string | null; busy: boolean; online: boolean;
  onObtainium: (() => void) | null; onCheckAgain(): void; onLogout(): void;
}) {
  const [confirming, setConfirming] = useState(false);
  return (
    <View style={{ gap: 12 }}>
      <Text variant="headlineMedium" accessibilityRole="header">Update Fytorium</Text>
      <Text>{props.minimum ? `This phone has Fytorium ${props.appVersion}. Your household's server needs ${props.minimum} or newer.` : "Your household's server needs a newer Fytorium."}</Text>
      <Button mode="contained" onPress={() => Linking.openURL(UPDATE_URL)} contentStyle={{ minHeight: 48 }}>Get the update</Button>
      {props.onObtainium && <Button mode="outlined" onPress={props.onObtainium} contentStyle={{ minHeight: 48 }}>Open Obtainium</Button>}
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Button onPress={props.onCheckAgain} disabled={props.busy} contentStyle={{ minHeight: 48 }}>Check again</Button>
        <Button onPress={() => setConfirming(true)} disabled={props.busy} contentStyle={{ minHeight: 48 }}>Log out</Button>
      </View>
      <Text style={{ minHeight: 48 }} numberOfLines={2}>{props.busy ? '' : props.message ?? ''}</Text>
      <LogoutDialog visible={confirming} online={props.online} onDismiss={() => setConfirming(false)} onConfirm={() => { setConfirming(false); props.onLogout(); }} />
    </View>
  );
}
