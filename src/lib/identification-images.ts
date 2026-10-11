import { identificationLimits } from './identification';

export async function shrinkIdentificationImage(file: File): Promise<Blob> {
  let bitmap: ImageBitmap | undefined;
  try {
    // createImageBitmap applies EXIF orientation by default.
    bitmap = await createImageBitmap(file);
    const scale = Math.min(1, identificationLimits.clientLongEdge / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext('2d');
    if (!context) return file;
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return await new Promise<Blob>(resolve => canvas.toBlob(blob => resolve(blob ?? file), 'image/jpeg', identificationLimits.clientJpegQuality));
  } catch {
    // The server may support a camera format this browser cannot decode.
    return file;
  } finally { bitmap?.close(); }
}
