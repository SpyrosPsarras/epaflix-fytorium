import { AppRegistry } from 'react-native';
import { registerRootComponent } from 'expo';
import Native from './modules/fytorium-native';
import App from './src/App';
import { refreshInBackground } from './src/refresh';

Native.firstRun();
AppRegistry.registerHeadlessTask('FytoriumRefresh', () => refreshInBackground);
registerRootComponent(App);
