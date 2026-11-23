import { buildMinioImage } from './photo-storage';

export async function setup() {
  await buildMinioImage();
}
