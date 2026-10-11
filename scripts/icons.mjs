import sharp from 'sharp';
import { copyFile, mkdir } from 'node:fs/promises';
await mkdir('static', { recursive: true });
await copyFile('assets/icon.svg', 'static/icon.svg');
for (const size of [192, 512]) {
  await sharp('assets/icon.svg').resize(size, size).png().toFile(`static/icon-${size}.png`);
}
