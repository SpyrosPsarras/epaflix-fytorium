import { Button, Dialog, Portal, Text } from 'react-native-paper';

export function LogoutDialog({ visible, online, onDismiss, onConfirm }: { visible: boolean; online: boolean; onDismiss(): void; onConfirm(): void }) {
  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss}>
        <Dialog.Title accessibilityRole="header">Log out of Fytorium?</Dialog.Title>
        <Dialog.Content>
          <Text>
            {online
              ? 'Plants and photos saved on this phone are removed, and this phone stops getting Fytorium notifications. You need a connection to log in again.'
              : "This phone is offline, so you can't log in again until it reconnects. Plants and photos saved on this phone are removed."}
          </Text>
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss} style={{ minHeight: 48, justifyContent: 'center' }}>Cancel</Button>
          <Button testID="logout-confirm" onPress={onConfirm} style={{ minHeight: 48, justifyContent: "center" }}>Log out</Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}
