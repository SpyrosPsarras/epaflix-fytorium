import type { Identity } from '$lib/server/auth';

declare global {
  namespace App {
    interface Locals { identity: Identity | null }
  }
}
export {};
