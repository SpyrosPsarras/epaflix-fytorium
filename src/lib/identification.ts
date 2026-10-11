// Allow five shrunk photos within 40 MB, below the runtime 41M body limit for multipart overhead.
export const identificationLimits = {
  imageBytes: 15 * 1024 * 1024,
  totalBytes: 40 * 1024 * 1024,
  serverLongEdge: 1600,
  clientLongEdge: 2048,
  clientJpegQuality: 0.85,
  // Includes uploading up to five downscaled images and waiting for identification.
  timeoutMs: 20_000,
} as const;

export const identificationQuotaExhausted = 'Pl@ntNet identification quota is exhausted. Try again tomorrow or search by name.';
