test('iOS defines the refresh task before a signed-in start schedules it', () => {
  const order: string[] = [];
  (globalThis as { ErrorUtils?: unknown }).ErrorUtils ??= {};
  jest.isolateModules(() => {
    jest.doMock('react-native', () => ({ AppRegistry: { registerHeadlessTask: () => order.push('headless') }, Platform: { OS: 'ios' } }));
    jest.doMock('expo', () => ({ registerRootComponent: () => order.push('root') }));
    jest.doMock('expo-task-manager', () => ({ defineTask: () => order.push('define') }));
    jest.doMock('expo-background-task', () => ({ BackgroundTaskResult: { Success: 1 } }));
    jest.doMock('../diagnostics', () => ({ record: () => undefined }));
    jest.doMock('../crash', () => ({ installCrashHandler: () => undefined }));
    jest.doMock('../refresh', () => ({ refreshInBackground: async () => undefined }));
    jest.doMock('../services', () => ({ BACKGROUND_TASK: 'task', care: {}, background: {}, credentials: { tokens: () => ({}) } }));
    jest.doMock('../session/start', () => ({ reconcileStart: ({ signedIn }: { signedIn: boolean }) => order.push(`start ${signedIn}`) }));
    jest.doMock('../App', () => ({ __esModule: true, default: () => null }));
    require('../../index');
  });
  expect(order).toEqual(['headless', 'define', 'start true', 'root']);
});
