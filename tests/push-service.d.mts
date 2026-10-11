import type { Server } from 'node:http';
import type { PushSubscription } from 'web-push';
export function createPushService(): {
  server: Server;
  vapid: { publicKey: string; privateKey: string };
  received: { endpoint: string; payload: { title: string; body: string; tag: string; url: string } }[];
  subscribe(): PushSubscription;
  respond(endpoint: string, status: number, delay?: number, bodyBytes?: number): void;
};
