import type { Server } from 'node:http';
import type { PushSubscription } from 'web-push';
export function createPushService(): {
  server: Server;
  fcm: {
    account: { client_email: string; private_key: string };
    received: { message: { token: string; data: Record<string, string> } }[];
    attempted: { message: { token: string; data: Record<string, string> } }[];
    oauthCalls: number;
    respond(token: string, status: number, code: string): void;
  };
  vapid: { publicKey: string; privateKey: string };
  received: { endpoint: string; payload: { title: string; body: string; tag: string; url: string } }[];
  attempted: { endpoint: string; payload: { title: string; body: string; tag: string; url: string } }[];
  subscribe(): PushSubscription;
  respond(endpoint: string, status: number, delay?: number, bodyBytes?: number): void;
};
