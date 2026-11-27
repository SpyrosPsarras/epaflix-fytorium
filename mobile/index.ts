import { AppRegistry, Platform } from 'react-native';
import { registerRootComponent } from 'expo';
import * as diagnostics from './src/diagnostics';
import { installCrashHandler } from './src/crash';
import { refreshInBackground } from './src/refresh';
import { BACKGROUND_TASK } from './src/services';
import App from './src/App';

installCrashHandler(ErrorUtils, () => diagnostics.record('APP_CRASH'));
AppRegistry.registerHeadlessTask('FytoriumRefresh', () => refreshInBackground);
if (Platform.OS === 'ios') {
  const TaskManager = require('expo-task-manager') as typeof import('expo-task-manager');
  const BackgroundTask = require('expo-background-task') as typeof import('expo-background-task');
  TaskManager.defineTask(BACKGROUND_TASK, async () => {
    await refreshInBackground();
    return BackgroundTask.BackgroundTaskResult.Success;
  });
}
registerRootComponent(App);
