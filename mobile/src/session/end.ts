// What a session end does once the credentials are gone, in order: push, background work, cached data, diagnostics.
export type EndDeps = {
  push: { sessionEnded(): Promise<void> };
  background: { stop(): void };
  care: { wipe(): void };
  diagnostics: { clear(): void; record(event: 'SESSION_ENDED'): void };
  session: { sessionChanged(): void };
};

export async function endSession(deps: EndDeps) {
  await deps.push.sessionEnded();
  deps.background.stop();
  deps.care.wipe();
  deps.diagnostics.clear();
  deps.diagnostics.record('SESSION_ENDED');
  deps.session.sessionChanged();
}
