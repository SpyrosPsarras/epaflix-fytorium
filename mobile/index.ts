import { AppRegistry } from 'react-native';
import { registerRootComponent } from 'expo';
import * as diagnostics from './src/diagnostics';
import { refreshInBackground } from './src/refresh';
import App from './src/App';

// Only the event name is kept: crash text can hold credentials.
const previous = ErrorUtils.getGlobalHandler();
ErrorUtils.setGlobalHandler((error, fatal) => {
  if (fatal) diagnostics.record('APP_CRASH');
  previous(error, fatal);
});
AppRegistry.registerHeadlessTask('FytoriumRefresh', () => refreshInBackground);
registerRootComponent(App);
