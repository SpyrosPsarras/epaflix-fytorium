// Online means the platform's own check passed; NetInfo runs no probe of its own (spec: no probe URL).
// Android reports a validated network as isInternetReachable. iOS reports no reachability without the probe, so
// NetInfo leaves it false there; iOS goes by isConnected alone.
export function isOnline(state: { isConnected: boolean | null; isInternetReachable: boolean | null | undefined }, os: string): boolean {
  if (state.isConnected !== true) return false;
  return os === 'ios' || state.isInternetReachable !== false;
}
