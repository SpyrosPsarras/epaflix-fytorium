type Handler = (error: unknown, fatal?: boolean) => void;
type ErrorUtilsLike = { getGlobalHandler(): Handler; setGlobalHandler(handler: Handler): void };

// Only the event name is kept and only a fixed error moves on: crash text can hold credentials.
export function installCrashHandler(errors: ErrorUtilsLike, record: () => void) {
  const previous = errors.getGlobalHandler();
  errors.setGlobalHandler((_error, fatal) => {
    if (fatal) record();
    previous(new Error(fatal ? 'Fytorium stopped after an error.' : 'Fytorium hit an error.'), fatal);
  });
}
