import { Client } from 'minio';
import { config } from './config';

let client: Client | undefined;
function storage() {
  const settings = config();
  client ??= new Client({
    endPoint: settings.minioEndpoint.hostname,
    port: Number(settings.minioEndpoint.port || (settings.minioEndpoint.protocol === 'https:' ? 443 : 80)),
    useSSL: settings.minioEndpoint.protocol === 'https:',
    accessKey: settings.minioAccessKey, secretKey: settings.minioSecretKey,
  });
  return client;
}
export async function storePhoto(key: string, bytes: Buffer) {
  await storage().putObject(config().minioBucket, key, bytes, bytes.length, { 'Content-Type': 'image/jpeg' });
}
export function readPhoto(key: string) {
  return storage().getObject(config().minioBucket, key);
}
export async function removePhotos(keys: string[]) {
  const errors = await storage().removeObjects(config().minioBucket, keys);
  if (errors.length) throw new Error('Photo storage could not delete objects');
}
