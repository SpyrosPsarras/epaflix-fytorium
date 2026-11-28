// Release versions are stable semver. Compare their core against the server's semver minimum.
export function requiresUpdate(status: number, body: string, current: string): boolean {
  if (status === 426) return true;
  if (status === 404 || status < 200 || status > 299) return false;
  let minimum = '0.0.0';
  try {
    const parsed = JSON.parse(body)?.minAppVersion;
    if (typeof parsed === 'string' && parsed) minimum = parsed;
  } catch {
    return false;
  }
  const version = (value: string) => value.split('+')[0].split('-')[0].split('.').map((part) => (/^\d+$/.test(part) ? Number(part) : 0));
  const required = version(minimum);
  const own = version(current);
  for (let i = 0; i < Math.max(required.length, own.length); i += 1) {
    const difference = (required[i] ?? 0) - (own[i] ?? 0);
    if (difference !== 0) return difference > 0;
  }
  return false;
}
