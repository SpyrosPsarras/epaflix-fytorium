import { care, credentials } from './services';

// The background read: Android's worker runs it as a headless task, iOS through expo-background-task.
export async function refreshInBackground(): Promise<void> {
  if (credentials.tokens()) await care.refresh();
}
