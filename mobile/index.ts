import { AppRegistry } from 'react-native';
import { registerRootComponent } from 'expo';
import * as diagnostics from './src/diagnostics';
import { installCrashHandler } from './src/crash';
import { refreshInBackground } from './src/refresh';
import App from './src/App';

installCrashHandler(ErrorUtils, () => diagnostics.record('APP_CRASH'));
AppRegistry.registerHeadlessTask('FytoriumRefresh', () => refreshInBackground);
registerRootComponent(App);
