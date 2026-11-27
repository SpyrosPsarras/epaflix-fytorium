import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useNavigation, usePreventRemove, useRoute, type NavigationAction } from '@react-navigation/native';
import { Button, Dialog, Portal, Text } from 'react-native-paper';
import { useTab } from './context';
import type { TabName } from './routes';

export type Question = { title: string; body: string; stay: string; leave: string };
export const DISCARD: Question = {
  title: 'Discard changes?', body: 'Your changes on this page are not saved.', stay: 'Keep editing', leave: 'Discard',
};

export type Hidden = { tab: TabName; question: Question };

type Shell = {
  // W6-P21: pages with a save, delete or upload in flight; tab changes wait for them.
  saving: Set<string>;
  // W6-P26: pages with unsaved changes, so log out can ask about one that a tab tap hid.
  dirty: Map<string, Hidden>;
  setDirty(page: string, entry: Hidden | null): void;
  // Pages whose question was already answered with leave elsewhere (W6-P15); they close without asking again.
  discarded: Set<string>;
  setSaving(page: string, saving: boolean): void;
  subscribe(listener: () => void): () => void;
};

const ShellContext = createContext<Shell | null>(null);

function createShell(): Shell {
  const listeners = new Set<() => void>();
  const saving = new Set<string>();
  const dirty = new Map<string, Hidden>();
  return {
    saving,
    dirty,
    discarded: new Set<string>(),
    setDirty(page, entry) {
      if (entry) dirty.set(page, entry); else dirty.delete(page);
    },
    setSaving(page, value) {
      const had = saving.has(page);
      if (value) saving.add(page); else saving.delete(page);
      if (had !== value) listeners.forEach((listener) => listener());
    },
    subscribe(listener) { listeners.add(listener); return () => { listeners.delete(listener); }; },
  };
}

export function ShellProvider({ children }: { children: ReactNode }) {
  const [shell] = useState(createShell);
  return <ShellContext.Provider value={shell}>{children}</ShellContext.Provider>;
}

export function useShell(): Shell {
  const shell = useContext(ShellContext);
  if (!shell) throw new Error('No shell');
  return shell;
}

// A page reports its request in flight; the shell holds tab taps, reselects and notification taps until it ends.
export function useSaving(page: string, saving: boolean) {
  const shell = useShell();
  useEffect(() => {
    shell.setSaving(page, saving);
    return () => shell.setSaving(page, false);
  }, [shell, page, saving]);
}

// W6-P18: a page with changes asks before it closes, whatever closes it: Back, the arrow, a reselect or a notification tap.
// Hiding it behind another tab or page never asks.
export function LeaveGuard({ dirty, question = DISCARD, onLeave }: { dirty: boolean; question?: Question; onLeave?: () => void }) {
  const navigation = useNavigation();
  const shell = useShell();
  const tab = useTab();
  const route = useRoute();
  const [pending, setPending] = useState<NavigationAction | null>(null);
  const [leaving, setLeaving] = useState<NavigationAction | null>(null);
  useEffect(() => {
    shell.setDirty(route.key, dirty ? { tab, question } : null);
    return () => shell.setDirty(route.key, null);
  }, [shell, route.key, dirty, tab, question]);
  usePreventRemove(dirty && !leaving, useCallback(({ data }: { data: { action: NavigationAction } }) => {
    if (shell.discarded.has(route.key)) setLeaving(data.action); else setPending(data.action);
  }, [shell, route.key]));
  // The confirmed action runs once the guard is off, so it is not stopped again.
  useEffect(() => { if (leaving) navigation.dispatch(leaving); }, [leaving, navigation]);
  return (
    <Portal>
      <Dialog visible={pending !== null} onDismiss={() => setPending(null)}>
        <Dialog.Title accessibilityRole="header">{question.title}</Dialog.Title>
        <Dialog.Content><Text>{question.body}</Text></Dialog.Content>
        <Dialog.Actions>
          <Button onPress={() => setPending(null)} contentStyle={{ minHeight: 48 }}>{question.stay}</Button>
          <Button
            onPress={() => {
              onLeave?.();
              setLeaving(pending);
              setPending(null);
            }}
            contentStyle={{ minHeight: 48 }}>
            {question.leave}
          </Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}

// The question a hidden page with changes asks, shown on its own (log out with a form left open in another tab).
export function QuestionDialog({ question, onStay, onLeave }: { question: Question | null; onStay(): void; onLeave(): void }) {
  return (
    <Portal>
      <Dialog visible={question !== null} onDismiss={onStay}>
        <Dialog.Title accessibilityRole="header">{question?.title}</Dialog.Title>
        <Dialog.Content><Text>{question?.body}</Text></Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onStay} contentStyle={{ minHeight: 48 }}>{question?.stay}</Button>
          <Button onPress={onLeave} contentStyle={{ minHeight: 48 }}>{question?.leave}</Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}
