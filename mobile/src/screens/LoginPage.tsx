import { useState } from 'react';
import { View } from 'react-native';
import { Button, Text, useTheme } from 'react-native-paper';

// W5a-P7 and P8: the line under Log in is reserved, so progress and errors never move the page.
// The parent keys this page by connection, so a tap made offline is forgotten when the phone reconnects.
export function LoginPage({ busy, message, online, onLogin }: { busy: boolean; message: string | null; online: boolean; onLogin(): void }) {
  const { colors } = useTheme();
  const [tappedOffline, setTappedOffline] = useState(false);
  const line = !online
    ? tappedOffline ? 'Logging in needs a connection. Turn on Wi-Fi or mobile data, then tap Log in.' : "You're logged out. Logging in needs a connection."
    : busy ? '' : message ?? '';
  return (
    <View style={{ gap: 16 }}>
      <Text variant="bodyLarge">Plant care for your household.</Text>
      <Button
        mode="contained" disabled={busy} onPress={() => (online ? onLogin() : setTappedOffline(true))}
        accessibilityState={{ disabled: busy }} accessibilityHint={online ? undefined : 'Logging in needs a connection.'}
        contentStyle={{ minHeight: 48 }}>
        {busy ? 'Logging in…' : 'Log in'}
      </Button>
      <Text style={{ minHeight: 48, color: colors.error }} numberOfLines={2}>{line}</Text>
      <Text style={{ color: colors.onSurfaceVariant }}>To join a household, open the invite link from its owner first, then log in here.</Text>
    </View>
  );
}
