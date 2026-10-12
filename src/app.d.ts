import type { Identity } from '$lib/server/auth';

declare global {
  namespace App {
    interface Error { message: string; reason?: 'rain_report_not_ready' }
    interface Locals { identity: Identity | null }
  }
}
export {};
