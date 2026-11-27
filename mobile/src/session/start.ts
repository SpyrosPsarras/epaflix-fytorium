// What a process start does with what the last one left: a signed-in start keeps its cache and makes sure the
// 6-hour read is scheduled (the scheduler keeps an existing one); a signed-out start removes what an interrupted
// logout left behind and any schedule, before anything can show.
export function reconcileStart(deps: {
  signedIn: boolean;
  care: { restore(): void; wipe(): void };
  background: { start(): void; stop(): void };
}) {
  if (deps.signedIn) {
    deps.care.restore();
    deps.background.start();
  } else {
    deps.care.wipe();
    deps.background.stop();
  }
}
